package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Tests for nested (hierarchical) states and composed (nested) state machines.
 *
 * These tests verify that the plugin correctly parses and renders:
 *   - Hierarchical states: states containing child states at multiple levels,
 *     with cross-level transitions and addInitialState/addFinalState patterns.
 *   - Composed state machines: an inline createStateMachine(...) call inside
 *     another machine's lambda, rendered as an atomic substate with its own
 *     internal structure.
 *
 * Each test follows the round-trip pattern: Kotlin DSL source → PSI parse →
 * State/Transition model → PlantUML diagram string, then compares the output
 * against the expected diagram.
 */
class NestedStatesAndComposedMachinesTest : BasePlatformTestCase() {

    fun testTwoLevelInlineNestedStates() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        initialState("State11")
                        state("State12")
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1 {
                    state "State11" as State11
                    state "State12" as State12
                    [*] --> State11
                  }
                  [*] --> State1
                }
                """
            ),
        )
    }

    fun testThreeLevelNestedStates() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    initialState("L1") {
                        initialState("L2") {
                            initialState("L3")
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "L1" as L1 {
                    state "L2" as L2 {
                      state "L3" as L3
                      [*] --> L3
                    }
                    [*] --> L2
                  }
                  [*] --> L1
                }
                """
            ),
        )
    }

    fun testCrossLevelTransitionToNephewState() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    initialState("State1") {
                        initialState("State11") {
                            transition<SwitchEvent> { targetState = state22 }
                        }
                    }
                    state("State2") {
                        initialState("State21")
                        val state22 = state("State22")
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State1" as State1 {
                    state "State11" as State11
                    [*] --> State11
                  }
                  state "State2" as State2 {
                    state "State21" as State21
                    state "State22" as State22
                    [*] --> State21
                  }
                  [*] --> State1
                }
                State11 --> State22 : SwitchEvent
                """
            ),
        )
    }

    fun testParentStateCarriesTransitionAndChildren() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        transition<PauseEvent> { targetState = state2 }
                        initialState("State11")
                        state("State12")
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1 {
                    state "State11" as State11
                    state "State12" as State12
                    [*] --> State11
                  }
                  [*] --> State1
                }
                State1 --> State2 : PauseEvent
                """
            ),
        )
    }

    fun testAddStateObjectPatternWithNesting() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    addInitialState(StateA) {
                        addInitialState(StateA1) {
                            transition<SwitchEvent>(targetState = StateA2)
                        }
                        addFinalState(StateA2)
                    }
                    addState(StateB)
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "StateA" as StateA {
                    state "StateA1" as StateA1
                    state "StateA2" as StateA2
                    [*] --> StateA1
                    StateA2 --> [*]
                  }
                  state "StateB" as StateB
                  [*] --> StateA
                }
                StateA1 --> StateA2 : SwitchEvent
                """
            ),
        )
    }

    fun testFinishedEventPatternWithNestedFinalState() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val state2 = state("State2")
                    initialState("State1") {
                        val state12 = finalState("State12")
                        initialState("State11") {
                            transition<SwitchEvent>(targetState = state12)
                        }
                        transition<FinishedEvent> { targetState = state2 }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State1" as State1 {
                    state "State12" as State12
                    state "State11" as State11
                    [*] --> State11
                    State12 --> [*]
                  }
                  [*] --> State1
                }
                State1 --> State2 : FinishedEvent
                State11 --> State12 : SwitchEvent
                """
            ),
        )
    }

    fun testMultipleParentStatesWithMixedChildren() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    initialState("Idle")
                    state("Active") {
                        initialState("Working")
                        state("Paused")
                    }
                    finalState("Done")
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "Idle" as Idle
                  state "Active" as Active {
                    state "Working" as Working
                    state "Paused" as Paused
                    [*] --> Working
                  }
                  state "Done" as Done
                  [*] --> Idle
                  Done --> [*]
                }
                """
            ),
        )
    }

    fun testInlineNestedMachineAppearsAsSubstate() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("outer") {
                    initialState("OuterState1")
                    createStateMachine("inner", start = false) {
                        initialState("InnerState1")
                        state("InnerState2")
                    }
                }
            """,
            expected = bodyTags(
                """
                state "outer" as outer {
                  state "OuterState1" as OuterState1
                  state "inner" as inner {
                    state "InnerState1" as InnerState1
                    state "InnerState2" as InnerState2
                    [*] --> InnerState1
                  }
                  [*] --> OuterState1
                }
                """
            ),
        )
    }

    fun testOuterTransitionTargetsInnerMachineByVariable() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("outer") {
                    val inner = createStateMachine("inner", start = false) {
                        initialState("InnerState1")
                    }
                    initialState("OuterState1") {
                        transition<SwitchEvent> { targetState = inner }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "outer" as outer {
                  state "inner" as inner {
                    state "InnerState1" as InnerState1
                    [*] --> InnerState1
                  }
                  state "OuterState1" as OuterState1
                  [*] --> OuterState1
                }
                OuterState1 --> inner : SwitchEvent
                """
            ),
        )
    }

    fun testInlineNestedMachineWithInternalTransitions() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("outer") {
                    createStateMachine("inner", start = false) {
                        val innerState2 = state("InnerState2")
                        initialState("InnerState1") {
                            transition<SwitchEvent> { targetState = innerState2 }
                        }
                    }
                    initialState("OuterState1")
                }
            """,
            expected = bodyTags(
                """
                state "outer" as outer {
                  state "inner" as inner {
                    state "InnerState2" as InnerState2
                    state "InnerState1" as InnerState1
                    [*] --> InnerState1
                  }
                  state "OuterState1" as OuterState1
                  [*] --> OuterState1
                }
                InnerState1 --> InnerState2 : SwitchEvent
                """
            ),
        )
    }
}