package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition

/**
 * Emits PlantUML state-diagram source for a parsed [StateMachine] tree.
 *
 * Names are sanitized into PlantUML identifiers and aliased back to the
 * original via `state "<orig>" as <id>`, so DSL state names containing
 * quotes / spaces / arrows still render correctly.
 *
 * Transitions resolve their `targetState = …` text against in-scope states
 * (siblings first, then ancestors). Unresolved targets are emitted as a
 * note next to the source — better than dropping them silently.
 */
object PlantUmlGenerator {

    fun render(machine: StateMachine, darkTheme: Boolean = false): String = buildString {
        appendLine("@startuml")
        appendLine("!pragma layout smetana")
        // Force vertical layout — the plugin's tool window is taller than it
        // is wide, so left-to-right diagrams overflow horizontally and require
        // constant scrolling. `top to bottom direction` is the official
        // PlantUML directive for this.
        appendLine("top to bottom direction")
        appendLine("hide empty description")
        if (darkTheme) appendDarkSkinparams()
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
        appendStateDecl(machine, ids, unnamedIdx, indent = 0)

        // Transitions are collected from every level so arrows cross nesting
        // boundaries. Choice states also emit an outgoing arrow to their
        // resolved redirect target when one is available.
        forEachStateWithAncestors(machine, ancestors = emptyList()) { source, ancestors ->
            source.transitions.forEach { t ->
                appendTransition(source, t, ancestors, ids, unnamedIdx)
            }
            if (source.kind.isChoice() && source.redirectTarget != null) {
                appendChoiceRedirect(source, ancestors, ids)
            }
        }

        appendLine("@enduml")
    }

    private fun StringBuilder.appendChoiceRedirect(
        source: State,
        ancestors: List<State>,
        ids: Map<State, String>,
    ) {
        val sourceId = ids[source] ?: return
        val target = resolveTarget(source.redirectTarget, source, ancestors)
        if (target != null) {
            appendLine("$sourceId --> ${ids.getValue(target)}")
        } else {
            // Unresolvable / external target — leave a hint instead of dropping it.
            appendLine("note right of $sourceId : → ${escape(source.redirectTarget!!)}")
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

    /**
     * Skinparams tuned to roughly match Darcula's editor palette so the
     * rendered PNG doesn't look out of place when embedded in the dark IDE.
     * Embedded in the source string itself — so a theme change naturally
     * busts the render cache (different source → cache miss → re-render).
     *
     * Public so other panels (Playground) can reuse the same block when
     * constructing their initial template — keeping a single source of truth
     * for the dark-theme styling.
     */
    fun darkThemeSkinparams(): String = buildString {
        appendLine("skinparam backgroundColor #2B2B2B")
        appendLine("skinparam DefaultFontColor #BBBBBB")
        appendLine("skinparam ArrowColor #9C9C9C")
        appendLine("skinparam ArrowFontColor #BBBBBB")
        appendLine("skinparam state {")
        appendLine("  BackgroundColor #3C3F41")
        appendLine("  BorderColor #9C9C9C")
        appendLine("  BorderThickness 1.5")
        appendLine("  FontColor #DDDDDD")
        appendLine("  StartColor #DDDDDD")
        appendLine("  EndColor #DDDDDD")
        appendLine("}")
        appendLine("skinparam note {")
        appendLine("  BackgroundColor #4C5052")
        appendLine("  BorderColor #9C9C9C")
        appendLine("  FontColor #BBBBBB")
        appendLine("}")
    }.trimEnd()

    private fun StringBuilder.appendDarkSkinparams() {
        appendLine(darkThemeSkinparams())
    }

    private fun isUnnamed(rawName: String): Boolean {
        val unquoted = rawName.trim('"')
        return unquoted.isBlank() || unquoted == "null" || unquoted == "<unnamed>"
    }

    private fun StringBuilder.appendStateDecl(
        state: State,
        ids: Map<State, String>,
        unnamedIdx: java.util.IdentityHashMap<Any, Int>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        val id = ids.getValue(state)
        val displayName = state.displayName(unnamedIdx[state])
        // PlantUML state-stereotype suffix — `<<choice>>` renders the state as
        // a conditional decision diamond, per
        // https://plantuml.com/state-diagram#1f8b7d76aeff5c0a .
        val stereotype = if (state.kind.isChoice()) " <<choice>>" else ""
        val header = if (displayName == id) {
            "state $id$stereotype"
        } else {
            "state \"${escape(displayName)}\" as $id$stereotype"
        }
        if (state.states.isEmpty()) {
            appendLine("$pad$header")
        } else if (state.isParallel) {
            // Parallel/orthogonal region — each direct child is its own
            // independent region, all active simultaneously. PlantUML's
            // separator for concurrent regions is `--` placed *between*
            // children inside the wrapping `state X { … }` block.
            //
            // Initial / final arrows are *per region* (inside each child),
            // not at this level — they're emitted naturally by the recursive
            // `appendStateDecl` for each child, since each region has its own
            // initial substate.
            appendLine("$pad$header {")
            state.states.forEachIndexed { idx, child ->
                if (idx > 0) appendLine("$pad  --")
                appendStateDecl(child, ids, unnamedIdx, indent + 1)
            }
            appendLine("$pad}")
        } else {
            appendLine("$pad$header {")
            state.states.forEach { appendStateDecl(it, ids, unnamedIdx, indent + 1) }
            state.states.firstOrNull { it.kind.isInitial() }?.let {
                appendLine("$pad  [*] --> ${ids[it]}")
            }
            // Final child → [*] arrow per PlantUML's terminal-state convention.
            // INITIAL_FINAL states correctly get both arrows ([*]→X and X→[*])
            // — they're entered first and also end the containing scope.
            state.states.filter { it.kind.isFinal() }.forEach { finalChild ->
                appendLine("$pad  ${ids[finalChild]} --> [*]")
            }
            appendLine("$pad}")
        }
    }

    private fun StringBuilder.appendTransition(
        source: State,
        transition: Transition,
        ancestors: List<State>,
        ids: Map<State, String>,
        unnamedIdx: java.util.IdentityHashMap<Any, Int>,
    ) {
        val sourceId = ids[source] ?: return
        val target = resolveTarget(transition.targetStateName, source, ancestors)
        val label = transitionLabel(transition, unnamedIdx[transition])
        if (target != null) {
            val targetId = ids.getValue(target)
            appendLine("$sourceId --> $targetId${if (label.isEmpty()) "" else " : $label"}")
        } else if (transition.targetStateName != null) {
            // Unresolved external/dynamic target — annotate so the user sees it.
            appendLine("note right of $sourceId : ${escape(label.ifEmpty { "Transition" })} → ${escape(transition.targetStateName)}")
        } else if (label.isNotEmpty()) {
            // Internal / target-less transition.
            appendLine("note right of $sourceId : $label (internal)")
        }
    }

    private fun transitionLabel(t: Transition, index: Int?): String {
        val explicit = t.name.takeIf { it.isNotBlank() && it != "<unnamed>" && it != "null" }
        return when {
            explicit != null -> escape(explicit)
            t.eventType != null -> "on ${escape(t.eventType)}"
            else -> if (index != null) "Transition #$index" else "Transition"
        }
    }

    private fun resolveTarget(
        targetText: String?,
        source: State,
        ancestors: List<State>,
    ): State? {
        if (targetText.isNullOrBlank()) return null
        // Strip surrounding braces from `transitionOn` lambda targets: "{ redState }" → "redState"
        // and any surrounding quotes from string-literal targets.
        val cleaned = targetText.trim()
            .removePrefix("{").removeSuffix("}").trim()
            .trim('"')
            .lowercase()
        // Look at siblings (including self) first, then walk up the ancestor chain.
        val scopes: List<List<State>> = listOf(source.states) +
            (listOf(source) + ancestors).map { it.states }
        for (scope in scopes) {
            scope.firstOrNull { it.name.trim('"').lowercase() == cleaned }?.let { return it }
        }
        return null
    }

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
        val cleaned = name.trim('"').replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (cleaned.isBlank() || !cleaned.first().isLetter() && cleaned.first() != '_') {
            "s_${cleaned.ifBlank { "x" }}"
        } else {
            cleaned
        }
    }

    private fun escape(text: String): String =
        text.trim('"').replace("\"", "\\\"").replace("\n", " ")

    // Friendly display name. Empty / "null" / "<unnamed>" become the bare type
    // word ("State" / "StateMachine") with an optional index — same convention
    // the tree view uses, so a label like "State #2" identifies the same node
    // in both views.
    private fun State.displayName(index: Int?): String {
        val raw = name.trim('"')
        if (raw.isBlank() || raw == "null" || raw == "<unnamed>") {
            val typeLabel = if (this is StateMachine) "StateMachine" else "State"
            return if (index != null) "$typeLabel #$index" else typeLabel
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
