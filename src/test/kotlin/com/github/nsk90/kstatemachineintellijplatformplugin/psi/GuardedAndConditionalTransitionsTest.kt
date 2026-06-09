package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtFile

/**
 * Tests for guarded transitions and conditional transitions.
 *
 * **Guarded transitions** (`guard = { ... }`) are a gate: the parser records
 * `isGuarded = true` and stores the raw guard expression text, but the
 * generator does NOT include the guard in the diagram — the arrow renders
 * exactly as it would without a guard. These tests freeze that "guard is
 * invisible in the diagram" contract and verify that target resolution still
 * works normally when a guard is present.
 *
 * **Conditional transitions** come in two flavours:
 *   - `transitionOn { targetState = { if/when … } }` — the parser walks
 *     return-position expressions via [extractDirectTargets]; each resolved
 *     branch becomes a separate [TargetGroup] and thus a separate arrow.
 *   - `transitionConditionally { direction = { … } }` — the parser walks all
 *     call descendants via [extractConditionalGroups], recognising:
 *       • `targetState(X)`         → plain arrow to X
 *       • `targetParallelStates(A,B)` → fork pseudo-state
 *       • `stay()`                 → self-loop arrow
 *       • `noTransition()`         → no arrow emitted
 *
 * Both callee styles can appear together with a `guard =` assignment; the guard
 * still has no effect on the rendered diagram.
 */
class GuardedAndConditionalTransitionsTest : BasePlatformTestCase() {

    // ── Guarded transitions ──────────────────────────────────────────────────

    fun testGuardedTransitionRendersArrowWithoutGuardExpression() {
        // The guard lambda is parsed (isGuarded = true) but never included in
        // the diagram label. The arrow to the target renders exactly as it
        // would without the guard.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        transition<SwitchEvent> {
                            guard = { condition }
                            targetState = state2
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State2 : SwitchEvent
                """
            ),
        )
    }

    fun testGuardedTransitionOnWithLambdaTarget() {
        // transitionOn { guard = {...}; targetState = { X } } — guard invisible,
        // target still resolved through the lambda via extractDirectTargets.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        transitionOn<SwitchEvent> {
                            guard = { condition }
                            targetState = { state2 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State2 : SwitchEvent
                """
            ),
        )
    }

    fun testMultipleGuardedTransitionsOnSameEventAllArrowsRendered() {
        // Multiple same-event transitionOns with different guards — the diagram
        // shows all possible targets regardless of guards (static analysis
        // cannot evaluate guards at parse time).
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    val state3 = state("State3")
                    initialState("State1") {
                        transitionOn<SwitchEvent> {
                            guard = { false }
                            targetState = { state2 }
                        }
                        transitionOn<SwitchEvent> {
                            guard = { true }
                            targetState = { state3 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State3" as State3
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State2 : SwitchEvent
                State1 --> State3 : SwitchEvent
                """
            ),
        )
    }

    // ── transitionOn — lambda-routed target ──────────────────────────────────

    fun testTransitionOnIfElseBranchingRendersTwoSeparateArrows() {
        // extractDirectTargets walks both branches of the if/else and produces
        // two TargetGroups, each rendered as a separate arrow from State1.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    val state3 = state("State3")
                    initialState("State1") {
                        transitionOn<SwitchEvent> {
                            targetState = { if (condition) state2 else state3 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State3" as State3
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State2 : SwitchEvent
                State1 --> State3 : SwitchEvent
                """
            ),
        )
    }

    fun testTransitionOnWhenBranchingRendersAllArrows() {
        // extractDirectTargets walks every when-entry expression, producing one
        // TargetGroup per resolved branch — three arrows from State1.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    val stateC = state("StateC")
                    initialState("State1") {
                        transitionOn<SwitchEvent> {
                            targetState = {
                                when (condition) {
                                    1 -> stateA
                                    2 -> stateB
                                    else -> stateC
                                }
                            }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state "StateC" as StateC
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> StateA : SwitchEvent
                State1 --> StateB : SwitchEvent
                State1 --> StateC : SwitchEvent
                """
            ),
        )
    }

    // ── transitionConditionally — direction-builder targets ──────────────────

    fun testTransitionConditionallyWithSingleTargetStateRendersArrow() {
        // direction = { targetState(X) } — unconditional single-target direction
        // produces one TargetGroup and one arrow.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        transitionConditionally<SwitchEvent> {
                            direction = { targetState(state2) }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State2 : SwitchEvent
                """
            ),
        )
    }

    fun testTransitionConditionallyStayRendersAsSelfLoop() {
        // stay() in the direction lambda maps to TargetGroup(isSelfLoop = true),
        // which the generator renders as a source-to-source arrow.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    initialState("State1") {
                        transitionConditionally<SwitchEvent> {
                            direction = { stay() }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State1 : SwitchEvent
                """
            ),
        )
    }

    fun testTransitionConditionallyNoTransitionEmitsNoArrow() {
        // noTransition() contributes no TargetGroup; the generator skips arrow
        // emission for transitionConditionally when targetGroups is empty
        // (unlike plain `transition` whose targetless form renders a self-loop).
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        transitionConditionally<SwitchEvent> {
                            direction = { noTransition() }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1
                  [*] --> State1
                }
                """
            ),
        )
    }

    fun testTransitionConditionallyIfElseBranchingRendersTwoArrows() {
        // extractConditionalGroups finds both targetState() calls inside the
        // if/else — each produces a separate TargetGroup and arrow, unlike
        // transitionOn which uses extractDirectTargets for the same shape.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    val state3 = state("State3")
                    initialState("State1") {
                        transitionConditionally<SwitchEvent> {
                            direction = {
                                if (condition) targetState(state2) else targetState(state3)
                            }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State3" as State3
                  state "State1" as State1
                  [*] --> State1
                }
                State1 --> State2 : SwitchEvent
                State1 --> State3 : SwitchEvent
                """
            ),
        )
    }

    fun testTransitionConditionallyWhenAllOutcomesRendered() {
        // when-expression with all four direction outcomes: two targetState
        // calls produce arrows, stay() produces a self-loop, noTransition()
        // produces nothing — matching the ComplexSyntaxSample pattern.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    initialState("Yellow") {
                        transitionConditionally<SwitchEvent> {
                            direction = {
                                when (condition) {
                                    0 -> targetState(stateA)
                                    1 -> targetState(stateB)
                                    2 -> stay()
                                    else -> noTransition()
                                }
                            }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state "Yellow" as Yellow
                  [*] --> Yellow
                }
                Yellow --> StateA : SwitchEvent
                Yellow --> StateB : SwitchEvent
                Yellow --> Yellow : SwitchEvent
                """
            ),
        )
    }

    fun testTransitionConditionallyTargetParallelStatesRendersFork() {
        // targetParallelStates(A, B) produces TargetGroup(isParallel = true,
        // targets = [A, B]). The generator emits a <<fork>> pseudo-state
        // declaration inside the enclosing block and two outgoing arrows from
        // the fork, mirroring the fork half of a fork/join pattern.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    val state3 = state("State3")
                    initialState("State1") {
                        transitionConditionally<SwitchEvent> {
                            direction = { targetParallelStates(state2, state3) }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State3" as State3
                  state "State1" as State1
                  [*] --> State1
                  state fork_State1_0 <<fork>>
                }
                State1 --> fork_State1_0 : SwitchEvent
                fork_State1_0 --> State2
                fork_State1_0 --> State3
                """
            ),
        )
    }

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
