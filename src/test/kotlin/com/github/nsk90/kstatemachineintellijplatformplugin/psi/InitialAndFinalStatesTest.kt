package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtFile

/**
 * Tests for `initialState`, `finalState`, `initialFinalState`,
 * `addInitialState`, and `addFinalState` declarations.
 *
 * The generator contract being frozen here:
 *   - Any state whose [StateKind] satisfies `isInitial()` causes `[*] --> <id>`
 *     to be emitted inside its enclosing block (only the *first* initial sibling
 *     contributes the arrow — that is what `firstOrNull { it.kind.isInitial() }`
 *     implements in [PlantUmlGenerator]).
 *   - Any state whose kind satisfies `isFinal()` causes `<id> --> [*]` to be
 *     emitted; there is no "first-only" restriction, so every final sibling gets
 *     its own exit arrow.
 *   - `initialFinalState` satisfies *both* predicates, so the generator emits
 *     both arrows for the same node.
 *   - `addInitialState(X)` and `addFinalState(X)` are the object-based forms;
 *     the parser maps them to the same INITIAL / FINAL kinds.
 *   - An unnamed `initialState()` bound to a `val` uses the variable name as
 *     both its display label and its diagram id.
 *
 * Each test is a round-trip: Kotlin DSL source → PSI parse → model →
 * PlantUML string, compared against an exact expected literal.
 */
class InitialAndFinalStatesTest : BasePlatformTestCase() {

    // ── initialState ─────────────────────────────────────────────────────────

    fun testInitialStateSoleChildEmitsInitialArrow() {
        // A machine that contains only an initialState — the simplest fixture
        // for the [*] --> X arrow.  No final state, so no exit arrow is emitted.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialState("Ready")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Ready" as Ready
                  [*] --> Ready
                }
                """
            ),
        )
    }

    fun testUnnamedInitialStateUsesBindingNameAsId() {
        // initialState() with no name argument — the parser records the state as
        // "<unnamed>" but assigns the Kotlin variable name ("ready") as its
        // bindingName.  preferredLabel() then surfaces "ready" as both the
        // display label and the sanitized id, so the diagram stays readable.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val ready = initialState()
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "ready" as ready
                  [*] --> ready
                }
                """
            ),
        )
    }

    fun testAddInitialStateEmitsInitialArrow() {
        // addInitialState(X) is the object-based form of initialState; the
        // parser maps it to StateKind.INITIAL via addStateKindFromCallee().
        // The generator must still emit [*] --> X inside the machine block.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    addInitialState(MyState)
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "MyState" as MyState
                  [*] --> MyState
                }
                """
            ),
        )
    }

    // ── finalState ───────────────────────────────────────────────────────────

    fun testFinalStateSoleChildEmitsFinalArrowNoInitialArrow() {
        // A machine that contains only a finalState: X --> [*] is emitted but
        // there is no [*] --> X, because no sibling satisfies isInitial().
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    finalState("Done")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Done" as Done
                  Done --> [*]
                }
                """
            ),
        )
    }

    fun testMultipleFinalStatesAllEmitFinalArrows() {
        // When several finalState siblings coexist, every one gets its own
        // X --> [*] exit arrow.  The entry arrow is still a single [*] --> Start
        // pointing at the unique initialState.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialState("Start")
                    finalState("Success")
                    finalState("Failure")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Start" as Start
                  state "Success" as Success
                  state "Failure" as Failure
                  [*] --> Start
                  Success --> [*]
                  Failure --> [*]
                }
                """
            ),
        )
    }

    fun testAddFinalStateEmitsFinalArrow() {
        // addFinalState(X) is the object-based form of finalState; the parser
        // maps it to StateKind.FINAL via addStateKindFromCallee().  The generator
        // must emit X --> [*] in the enclosing block.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    addFinalState(DoneState)
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "DoneState" as DoneState
                  DoneState --> [*]
                }
                """
            ),
        )
    }

    fun testInitialAndFinalStateWithTransitionBetweenThem() {
        // Canonical one-shot workflow: initialState transitions to finalState on
        // an event.  Verifies that the entry arrow, the exit arrow, and the
        // transition arrow are all rendered correctly in the same diagram.  The
        // states are declared via `val` bindings so target resolution goes
        // through resolveLocalInitializer().
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val done = finalState("Done")
                    val start = initialState("Start") {
                        transition<SwitchEvent> { targetState = done }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "Done" as Done
                  state "Start" as Start
                  [*] --> Start
                  Done --> [*]
                }
                Start --> Done : SwitchEvent
                """
            ),
        )
    }

    // ── initialFinalState ────────────────────────────────────────────────────

    fun testInitialFinalStateGetsBothArrows() {
        // initialFinalState satisfies both isInitial() and isFinal(), so the
        // generator must emit [*] --> X AND X --> [*] for the same state node.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialFinalState("done")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "done" as done
                  [*] --> done
                  done --> [*]
                }
                """
            ),
        )
    }

    fun testInitialFinalStateInsideParentGetsBothArrowsInParentBlock() {
        // initialFinalState nested inside a parent state — the dual arrows must
        // appear *inside* the parent's block, not at the machine level.  This
        // is the KStateMachine FinishedEvent composition pattern: when the
        // parent reaches its internal final state it fires FinishedEvent, and
        // the parent itself carries the outgoing FinishedEvent transition.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        initialFinalState("Finished")
                        transition<FinishedEvent> { targetState = state2 }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1 {
                    state "Finished" as Finished
                    [*] --> Finished
                    Finished --> [*]
                  }
                  [*] --> State1
                }
                State1 --> State2 : FinishedEvent
                """
            ),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun assertPlantUml(source: String, expected: String) {
        val file = myFixture.configureByText("Test.kt", source.trimIndent()) as KtFile
        val machines = PsiElementsParser { /* discard log output */ }.parse(file)
        require(machines.size == 1) {
            "Expected exactly one state machine in source, got ${machines.size}"
        }
        val rendered = PlantUmlGenerator.render(machines.single()).trim()
        rendered shouldBe expected.trimIndent().trim()
    }

    private fun bodyTags(expected: String) = """
        @startuml
        top to bottom direction
        hide empty description

${expected.trimIndent().trim().prependIndent("        ")}
        @enduml
    """.trimIndent()
}
