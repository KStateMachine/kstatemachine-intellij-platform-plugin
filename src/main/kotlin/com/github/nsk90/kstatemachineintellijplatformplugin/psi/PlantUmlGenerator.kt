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

    fun render(machine: StateMachine): String = buildString {
        appendLine("@startuml")
        appendLine("!pragma layout smetana")
        // Force vertical layout — the plugin's tool window is taller than it
        // is wide, so left-to-right diagrams overflow horizontally and require
        // constant scrolling. `top to bottom direction` is the official
        // PlantUML directive for this.
        appendLine("top to bottom direction")
        appendLine("hide empty description")
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

        // Transitions are collected from every level so arrows cross nesting boundaries.
        forEachStateWithAncestors(machine, ancestors = emptyList()) { source, ancestors ->
            source.transitions.forEach { t ->
                appendTransition(source, t, ancestors, ids, unnamedIdx)
            }
        }

        appendLine("@enduml")
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
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        val id = ids.getValue(state)
        val displayName = state.displayName(unnamedIdx[state])
        val header = if (displayName == id) {
            "state $id"
        } else {
            "state \"${escape(displayName)}\" as $id"
        }
        if (state.states.isEmpty()) {
            appendLine("$pad$header")
        } else {
            appendLine("$pad$header {")
            state.states.forEach { appendStateDecl(it, ids, unnamedIdx, indent + 1) }
            state.states.firstOrNull { it.kind.isInitial() }?.let {
                appendLine("$pad  [*] --> ${ids[it]}")
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
        val cleaned = targetText.trim().removePrefix("{").removeSuffix("}").trim()
        // Look at siblings (including self) first, then walk up the ancestor chain.
        val scopes: List<List<State>> = listOf(source.states) +
            (listOf(source) + ancestors).map { it.states }
        for (scope in scopes) {
            scope.firstOrNull { it.name == cleaned || matchesIdentifier(it.name, cleaned) }?.let { return it }
        }
        return null
    }

    private fun matchesIdentifier(stateName: String, targetText: String): Boolean {
        // KStateMachine users frequently do `val redState = state("red")` and write
        // `targetState = redState`. Accept a loose name match in either direction
        // so the arrow still draws.
        val a = stateName.trim('"').lowercase()
        val b = targetText.trim('"').lowercase()
        return a == b || b.startsWith(a) || a.startsWith(b)
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
}
