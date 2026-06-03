package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition

/**
 * Emits state-diagram source for a parsed [StateMachine] tree, in either
 * PlantUML or Mermaid stateDiagram-v2 syntax.
 *
 * PlantUML and Mermaid v2 share most of their state-diagram surface (the
 * `state X { … }` block, `--` parallel separator, `[*]` pseudo-state,
 * `<<choice>>` stereotype, `A --> B : event` transitions, `note right of …`).
 * Only the header (preamble + theme directive), the footer, and the layout-
 * direction directive differ — those branch on [DiagramSyntax]; the rest of
 * the emission walks the model identically.
 *
 * Names are sanitized into identifiers and aliased back to the original via
 * `state "<orig>" as <id>`, so DSL names with quotes / spaces / arrows still
 * render correctly. Transitions resolve their `targetState = …` text against
 * in-scope states; unresolved targets become side-notes.
 */
object PlantUmlGenerator {

    fun render(
        machine: StateMachine,
        darkTheme: Boolean = false,
        syntax: DiagramSyntax = DiagramSyntax.PLANTUML,
    ): String = buildString {
        // `darkTheme` is now applied by the renderers themselves (the TeaVM
        // PlantUML build takes a `{dark: true}` option; Mermaid uses its
        // `%%{init: {'theme':'dark'}}%%` directive). The generator still
        // forwards the flag to the header for the Mermaid front-matter; the
        // PlantUML header no longer carries any theme markup.
        appendHeader(syntax, darkTheme)
        appendLine()

        // Index unnamed states / transitions / nested machines per-machine,
        // matching the tree's convention so labels like "State #2" reference
        // the same node across both views.
        val unnamedIdx = java.util.IdentityHashMap<Any, Int>()
        indexMachineContent(machine, unnamedIdx)

        val ids = mutableMapOf<State, String>()
        assignIds(machine, ids, taken = mutableSetOf("start", "end"))

        // The machine itself is a State — render it as a wrapping `state Name { … }`
        // block. Children are emitted recursively, including any nested machines
        // (which get the same wrap-in-named-block treatment).
        appendStateDecl(machine, ids, unnamedIdx, syntax, parentAncestors = emptyList(), indent = 0)

        // Global transition pass — emits arrows for every state that's NOT
        // already had its transitions emitted inline by a parallel parent.
        forEachStateWithAncestors(machine, ancestors = emptyList()) { source, ancestors ->
            // Inside a parallel region: skip — the region's parallel parent
            // already inlined this state's transitions next to the region body.
            if (ancestors.any { it.isParallel }) return@forEachStateWithAncestors
            source.transitions.forEach { t ->
                appendTransition(source, t, ancestors, ids, unnamedIdx, indent = 0)
            }
            if (source.kind.isChoice() && source.redirectTargets.isNotEmpty()) {
                appendChoiceRedirect(source, ancestors, ids, indent = 0)
            }
        }

        appendFooter(syntax)
    }

    private fun StringBuilder.appendHeader(syntax: DiagramSyntax, darkTheme: Boolean) {
        when (syntax) {
            DiagramSyntax.PLANTUML -> {
                appendLine("@startuml")
                // Force vertical layout — the plugin's tool window is taller
                // than it is wide, so left-to-right diagrams overflow
                // horizontally. `top to bottom direction` is PlantUML's
                // directive for this. No `!pragma layout` directive — the
                // TeaVM build uses Viz.js/Graphviz dot by default, which is
                // what we want (Smetana was the source of the parallel-state
                // bugs that drove this whole rework).
                appendLine("top to bottom direction")
                appendLine("hide empty description")
            }
            DiagramSyntax.MERMAID -> {
                // Mermaid's init directive must come BEFORE the diagram type
                // declaration. `theme: dark` switches the built-in palette.
                if (darkTheme) appendLine("%%{init: {'theme': 'dark'}}%%")
                appendLine("stateDiagram-v2")
                // `direction TB` mirrors PlantUML's `top to bottom direction`.
                appendLine("    direction TB")
            }
        }
    }

    private fun StringBuilder.appendFooter(syntax: DiagramSyntax) {
        when (syntax) {
            DiagramSyntax.PLANTUML -> appendLine("@enduml")
            DiagramSyntax.MERMAID -> {} // Mermaid has no terminator
        }
    }

    private fun StringBuilder.appendChoiceRedirect(
        source: State,
        ancestors: List<State>,
        ids: Map<State, String>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        val sourceId = ids[source] ?: return
        source.redirectTargets.forEach { targetText ->
            val resolved = resolveTarget(targetText, source, ancestors)
            val targetId = if (resolved != null) ids.getValue(resolved) else sanitizeId(targetText)
            appendLine("$pad$sourceId --> $targetId")
        }
    }

    /** Walk a machine and assign 1-based indices to every unnamed node within it. */
    private fun indexMachineContent(machine: StateMachine, out: java.util.IdentityHashMap<Any, Int>) {
        var stateCounter = 0
        var transitionCounter = 0
        var nestedMachineCounter = 0
        fun walk(node: State) {
            for (child in node.states) {
                if (child is StateMachine) {
                    if (isUnnamed(child.name)) {
                        nestedMachineCounter++
                        out[child] = nestedMachineCounter
                    }
                    indexMachineContent(child, out)
                } else {
                    if (isUnnamed(child.name)) {
                        stateCounter++
                        out[child] = stateCounter
                    }
                    walk(child)
                }
            }
            for (t in node.transitions) {
                val raw = t.name
                if (raw.isBlank() || raw == "null" || raw == "<unnamed>") {
                    transitionCounter++
                    out[t] = transitionCounter
                }
            }
        }
        walk(machine)
    }

    private fun isUnnamed(rawName: String): Boolean {
        val unquoted = rawName.trim('"')
        return unquoted.isBlank() || unquoted == "null" || unquoted == "<unnamed>"
    }

    private fun StringBuilder.appendStateDecl(
        state: State,
        ids: Map<State, String>,
        unnamedIdx: java.util.IdentityHashMap<Any, Int>,
        syntax: DiagramSyntax,
        parentAncestors: List<State>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        val id = ids.getValue(state)
        val displayName = state.displayName(unnamedIdx[state])
        // `<<choice>>` stereotype is identical in PlantUML and Mermaid v2 —
        // both render the state as a conditional decision diamond.
        val stereotype = if (state.kind.isChoice()) " <<choice>>" else ""
        // Always emit the aliased `state "name" as id` form. The shorter
        // `state id` shorthand works in PlantUML but Mermaid stateDiagram-v2
        // silently drops simple `state foo` declarations that have no body,
        // leaving any arrows that reference `foo` pointing at an implicit
        // node with no label. The aliased form renders identically in both.
        val header = "state \"${escape(displayName)}\" as $id$stereotype"
        if (state.states.isEmpty()) {
            appendLine("$pad$header")
        } else if (state.isParallel) {
            // Parallel/orthogonal region — each direct child is its own
            // independent region, all active simultaneously. Both PlantUML
            // and Mermaid v2 separate regions with `--`. The transitions for
            // everything inside each region must be emitted INSIDE the region
            // block (between the region's state body and the next `--` /
            // closing brace) — if they're hoisted to the bottom like in
            // non-parallel diagrams, the parser misreads them and the second
            // region disappears.
            appendLine("$pad$header {")
            val regionAncestors = listOf(state) + parentAncestors
            state.states.forEachIndexed { idx, child ->
                if (idx > 0) appendLine("$pad  --")
                appendStateDecl(child, ids, unnamedIdx, syntax, regionAncestors, indent + 1)
                // Emit the region's *own* outgoing arrows at the parallel
                // parent's body indent — the correct scope for transitions
                // whose source is the region root itself. Deeper arrows are
                // handled inside the recursive appendStateDecl call above,
                // at the indent of their own source state's enclosing block.
                child.transitions.forEach { t ->
                    appendTransition(child, t, regionAncestors, ids, unnamedIdx, indent + 1)
                }
                if (child.kind.isChoice() && child.redirectTarget != null) {
                    appendChoiceRedirect(child, regionAncestors, ids, indent + 1)
                }
                // Mermaid requires an explicit `[*] --> region` arrow inside
                // each parallel region's slot — without it the renderer fails
                // to draw the region. PlantUML doesn't tolerate this construct
                // at the parallel-parent scope (each region's own initial is
                // declared inside the region's own block), so we emit only
                // for Mermaid output.
                if (syntax == DiagramSyntax.MERMAID) {
                    appendLine("$pad  [*] --> ${ids.getValue(child)}")
                }
            }
            appendForkDeclarations(state, ids, indent + 1)
            appendLine("$pad}")
        } else {
            appendLine("$pad$header {")
            val innerAncestors = listOf(state) + parentAncestors
            // Inside a parallel ancestor scope we MUST emit each child's
            // outgoing arrows in the lexical block where the child is
            // declared — otherwise PlantUML/Smetana can't decide which region
            // owns the arrow and the parse fails. Outside a parallel scope
            // the global pass at the bottom of render() handles them.
            val insideParallel = parentAncestors.any { it.isParallel }
            state.states.forEach { child ->
                appendStateDecl(child, ids, unnamedIdx, syntax, innerAncestors, indent + 1)
                if (insideParallel) {
                    child.transitions.forEach { t ->
                        appendTransition(child, t, innerAncestors, ids, unnamedIdx, indent + 1)
                    }
                    if (child.kind.isChoice() && child.redirectTarget != null) {
                        appendChoiceRedirect(child, innerAncestors, ids, indent + 1)
                    }
                }
            }
            state.states.firstOrNull { it.kind.isInitial() }?.let {
                appendLine("$pad  [*] --> ${ids[it]}")
            }
            // Final child → [*] arrow. INITIAL_FINAL states get both arrows
            // ([*]→X and X→[*]) — they're entered first and end the scope.
            state.states.filter { it.kind.isFinal() }.forEach { finalChild ->
                appendLine("$pad  ${ids[finalChild]} --> [*]")
            }
            appendForkDeclarations(state, ids, indent + 1)
            appendLine("$pad}")
        }
    }

    private fun StringBuilder.appendTransition(
        source: State,
        transition: Transition,
        ancestors: List<State>,
        ids: Map<State, String>,
        unnamedIdx: java.util.IdentityHashMap<Any, Int>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        val sourceId = ids[source] ?: return
        val label = transitionLabel(transition, unnamedIdx[transition])
        val labelSuffix = if (label.isEmpty()) "" else " : $label"

        if (transition.targetGroups.isEmpty()) {
            // No statically-resolvable target. Only emit a self-loop for DSL
            // forms that legitimately permit an omitted `targetState` (regular
            // `transition`/`dataTransition`, or no recognized callee). For
            // lambda-routed forms whose target couldn't be parsed (e.g. a
            // `transitionConditionally` with no `direction =`, or a
            // `transitionOn { targetState = { … } }` whose body has no
            // statically-resolvable identifier) emitting a self-loop would
            // mislead the reader — skip instead.
            if (transition.callee.supportsTargetlessSemantics()) {
                appendLine("$pad$sourceId --> $sourceId$labelSuffix")
            }
            return
        }

        transition.targetGroups.forEachIndexed { groupIndex, group ->
            when {
                group.isSelfLoop -> {
                    // `transitionConditionally { direction = { stay() } }` —
                    // fires the transition but stays in the source state. Drawn
                    // as a self-loop so the user can see the transition exists.
                    appendLine("$pad$sourceId --> $sourceId$labelSuffix")
                }
                group.targets.isEmpty() -> Unit // shouldn't happen — parser drops empty groups
                group.isParallel && group.targets.size >= 2 -> {
                    // Atomic parallel split: arrows from the source to a
                    // `<<fork>>` pseudo-state, then arrows from the fork to each
                    // parallel target. The fork's DECLARATION is emitted
                    // separately by `appendForkDeclarations` inside the source's
                    // parent block — otherwise PlantUML draws it floating
                    // outside the enclosing state, which the user reported.
                    val forkId = forkId(sourceId, groupIndex)
                    appendLine("$pad$sourceId --> $forkId$labelSuffix")
                    group.targets.forEach { targetText ->
                        val resolved = resolveTarget(targetText, source, ancestors)
                        val targetId = if (resolved != null) ids.getValue(resolved) else sanitizeId(targetText)
                        appendLine("$pad$forkId --> $targetId")
                    }
                }
                else -> {
                    // Branch outcome (single-target group, or a singleton parallel
                    // group treated as a regular target — same arrow shape).
                    group.targets.forEach { targetText ->
                        val resolved = resolveTarget(targetText, source, ancestors)
                        val targetId = if (resolved != null) ids.getValue(resolved) else sanitizeId(targetText)
                        appendLine("$pad$sourceId --> $targetId$labelSuffix")
                    }
                }
            }
        }
    }

    private fun String?.supportsTargetlessSemantics(): Boolean =
        this == null || this == "transition" || this == "dataTransition"

    /** Stable per-(source, group-index) fork pseudo-state identifier. */
    private fun forkId(sourceId: String, groupIndex: Int): String = "fork_${sourceId}_$groupIndex"

    /**
     * Emit `state fork_<src>_<i> <<fork>>` declarations inside [parent]'s body
     * for every parallel-split transition of every direct child of [parent].
     * Keeping the declaration co-located with the source state's enclosing
     * block makes PlantUML's parser bind the fork to that scope; if it were
     * left at the top-level (where arrows live) PlantUML would float the fork
     * outside its containing state.
     *
     * Walks only direct children — deeper descendants get their forks emitted
     * by their own enclosing `appendStateDecl` call via the recursive nature
     * of the rendering pass.
     */
    private fun StringBuilder.appendForkDeclarations(
        parent: State,
        ids: Map<State, String>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        parent.states.forEach { child ->
            val sourceId = ids[child] ?: return@forEach
            child.transitions.forEach { transition ->
                transition.targetGroups.forEachIndexed { groupIndex, group ->
                    if (group.isParallel && group.targets.size >= 2 && !group.isSelfLoop) {
                        appendLine("${pad}state ${forkId(sourceId, groupIndex)} <<fork>>")
                    }
                }
            }
        }
    }

    private fun transitionLabel(t: Transition, index: Int?): String {
        val explicit = t.name.takeIf { it.isNotBlank() && it != "<unnamed>" && it != "null" }
        return when {
            explicit != null -> escape(explicit)
            t.eventType != null -> escape(t.eventType)
            else -> if (index != null) "Transition $index" else "Transition"
        }
    }

    private fun resolveTarget(
        targetText: String?,
        source: State,
        ancestors: List<State>,
    ): State? {
        if (targetText.isNullOrBlank()) return null
        val cleaned = targetText.trim()
            .removePrefix("{").removeSuffix("}").trim()
            .trim('"')
            .lowercase()
        // Walk the entire machine subtree rooted at the outermost ancestor.
        // KStateMachine permits `targetParallelStates(deepStateInOtherRegion)`
        // — restricting to the source's immediate scope (the old behaviour)
        // misses those cross-region references and the diagram then invents
        // a phantom state with the raw target text as its id.
        val root = ancestors.lastOrNull() ?: source
        return findStateInSubtree(root, cleaned)
    }

    private fun findStateInSubtree(state: State, cleanedName: String): State? {
        if (state.matchesTargetName(cleanedName)) return state
        for (child in state.states) {
            findStateInSubtree(child, cleanedName)?.let { return it }
        }
        return null
    }

    /**
     * Matches by the state's logical [State.name] OR its [State.bindingName]
     * (the Kotlin variable the state was assigned to). The binding-name path
     * catches anonymous states — typically `choiceState { … }` calls held in
     * a `val foo = …` / `foo = …` — that have no [State.name] of their own
     * but are referenced by their variable name in transition targets.
     */
    private fun State.matchesTargetName(cleanedName: String): Boolean =
        name.trim('"').lowercase() == cleanedName ||
            bindingName?.lowercase() == cleanedName

    private fun assignIds(state: State, out: MutableMap<State, String>, taken: MutableSet<String>) {
        val base = sanitizeId(state.name)
        var id = base
        var i = 2
        while (id in taken) {
            id = "${base}_$i"
            i++
        }
        taken += id
        out[state] = id
        state.states.forEach { assignIds(it, out, taken) }
    }

    private fun sanitizeId(name: String): String {
        val cleaned = name.trim('"')
            .replace(Regex("[^A-Za-z0-9_]"), "_")
            .trim('_')
            .replace(Regex("_+"), "_")
        return when {
            cleaned.isBlank() -> "s_x"
            !cleaned.first().isLetter() && cleaned.first() != '_' -> "s_$cleaned"
            else -> cleaned
        }
    }

    private fun escape(text: String): String =
        text.trim('"').replace("\"", "\\\"").replace("\n", " ")

    private fun State.displayName(index: Int?): String {
        val raw = name.trim('"')
        if (raw.isBlank() || raw == "null" || raw == "<unnamed>") {
            val typeLabel = if (this is StateMachine) "StateMachine" else "State"
            return if (index != null) "$typeLabel $index" else typeLabel
        }
        return raw
    }

    private fun forEachStateWithAncestors(
        state: State,
        ancestors: List<State>,
        action: (State, List<State>) -> Unit,
    ) {
        action(state, ancestors)
        val newAncestors = listOf(state) + ancestors
        state.states.forEach { forEachStateWithAncestors(it, newAncestors, action) }
    }

    private fun StateKind.isChoice(): Boolean = when (this) {
        StateKind.CHOICE,
        StateKind.INITIAL_CHOICE,
        StateKind.CHOICE_DATA,
        StateKind.INITIAL_CHOICE_DATA -> true
        else -> false
    }

    private fun StateKind.isInitial(): Boolean = when (this) {
        StateKind.INITIAL,
        StateKind.INITIAL_DATA,
        StateKind.INITIAL_FINAL,
        StateKind.INITIAL_FINAL_DATA,
        StateKind.INITIAL_MUTABLE_DATA,
        StateKind.INITIAL_FINAL_MUTABLE_DATA,
        StateKind.INITIAL_CHOICE,
        StateKind.INITIAL_CHOICE_DATA -> true
        else -> false
    }

    private fun StateKind.isFinal(): Boolean = when (this) {
        StateKind.FINAL,
        StateKind.INITIAL_FINAL,
        StateKind.FINAL_DATA,
        StateKind.INITIAL_FINAL_DATA,
        StateKind.FINAL_MUTABLE_DATA,
        StateKind.INITIAL_FINAL_MUTABLE_DATA -> true
        else -> false
    }
}
