package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Tests covering parallel states (orthogonal regions) in KStateMachine.
 *
 * A parallel state has `childMode = ChildMode.PARALLEL` (named arg) or a positional
 * `ChildMode.PARALLEL` argument. All direct children become simultaneously-active regions
 * separated by `--` in the PlantUML diagram.
 *
 * **Parser behaviour (`isParallelChildMode`):**
 *   - Named arg: `state("P", childMode = ChildMode.PARALLEL)` — arg name "childMode" ✓
 *   - Positional: `state("P", ChildMode.PARALLEL)` — arg text ends with ".PARALLEL" ✓
 *   - Machine-level: `createStateMachine("m", childMode = ChildMode.PARALLEL)` — same rule
 *
 * **Generator behaviour for parallel parent blocks:**
 *   - Each direct child region is rendered as a separate state block.
 *   - Regions are separated by `--` (zero separators for 1 region, N-1 for N regions).
 *   - Each region slot ends with `[*] --> regionId` (required by Mermaid; also emitted
 *     for PlantUML for consistency).
 *   - Outgoing transitions of states that live *inside* a parallel ancestor are emitted
 *     **inline** (inside the nearest enclosing block), not in the global transition pass.
 *     The global pass skips any state whose ancestor list contains a parallel state.
 *   - Fork / join pseudo-states are handled by ForkAndJoinTest; this file focuses on
 *     the structural region layout and transition-inlining behaviour.
 *
 * Source samples are drawn from ParallelRegionListenersSample, FinishedStateSample,
 * and ParallelStatesTest / ParallelTargetStatesTest in the KStateMachine library.
 */
class ParallelStatesTest : BasePlatformTestCase() {

    // ── Basic two-region parallel state ──────────────────────────────────────

    fun testTwoRegionParallelStateSeparatedByDoubleDash() {
        // Minimal parallel state: two leaf regions inside a machine. The generator
        // emits the `--` separator between them and a `[*] --> regionId` for each.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Work", childMode = ChildMode.PARALLEL) {
                        state("Region1")
                        state("Region2")
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Work" as Work {
                    state "Region1" as Region1
                    [*] --> Region1
                    --
                    state "Region2" as Region2
                    [*] --> Region2
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    fun testThreeRegionParallelStateHasTwoSeparators() {
        // Three regions → two `--` separators. Verifies the forEachIndexed guard
        // (`if (idx > 0) appendLine("--")`) rather than a fixed separator count.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Work", childMode = ChildMode.PARALLEL) {
                        state("Region1")
                        state("Region2")
                        state("Region3")
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Work" as Work {
                    state "Region1" as Region1
                    [*] --> Region1
                    --
                    state "Region2" as Region2
                    [*] --> Region2
                    --
                    state "Region3" as Region3
                    [*] --> Region3
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── Machine-level parallel (top-level childMode) ──────────────────────────

    fun testParallelMachineAtTopLevelTwoRegionsWithTransitions() {
        // ParallelRegionListenersSample pattern: the machine itself has
        // `childMode = ChildMode.PARALLEL`. Both direct child states become regions.
        // Each region has its own initial/final states and an intra-region transition.
        // The global transition pass skips everything inside the top-level parallel
        // machine — all arrows are emitted inline.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m", childMode = ChildMode.PARALLEL) {
                    state("Region1") {
                        val done1 = finalState("Done1")
                        initialState("Active1") {
                            transition<Event1> { targetState = done1 }
                        }
                    }
                    state("Region2") {
                        val done2 = finalState("Done2")
                        initialState("Active2") {
                            transition<Event2> { targetState = done2 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Region1" as Region1 {
                    state "Done1" as Done1
                    state "Active1" as Active1
                    Active1 --> Done1 : Event1
                    [*] --> Active1
                    Done1 --> [*]
                  }
                  [*] --> Region1
                  --
                  state "Region2" as Region2 {
                    state "Done2" as Done2
                    state "Active2" as Active2
                    Active2 --> Done2 : Event2
                    [*] --> Active2
                    Done2 --> [*]
                  }
                  [*] --> Region2
                }
                """
            ),
        )
    }

    // ── Transition inlining inside parallel regions ───────────────────────────

    fun testTransitionsInsideParallelRegionsAreEmittedInline() {
        // When a state lives inside a parallel ancestor, its outgoing transitions
        // must be emitted inside the nearest enclosing block — the global transition
        // pass skips them (ancestors.any { it.isParallel } = true). This test
        // verifies that arrows from StateA1 and StateB1 appear inside their region
        // blocks, not at the top level.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("Active", childMode = ChildMode.PARALLEL) {
                        state("RegionA") {
                            val a2 = state("StateA2")
                            initialState("StateA1") {
                                transition<EventA> { targetState = a2 }
                            }
                        }
                        state("RegionB") {
                            val b2 = state("StateB2")
                            initialState("StateB1") {
                                transition<EventB> { targetState = b2 }
                            }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Active" as Active {
                    state "RegionA" as RegionA {
                      state "StateA2" as StateA2
                      state "StateA1" as StateA1
                      StateA1 --> StateA2 : EventA
                      [*] --> StateA1
                    }
                    [*] --> RegionA
                    --
                    state "RegionB" as RegionB {
                      state "StateB2" as StateB2
                      state "StateB1" as StateB1
                      StateB1 --> StateB2 : EventB
                      [*] --> StateB1
                    }
                    [*] --> RegionB
                  }
                  [*] --> Active
                }
                """
            ),
        )
    }

    fun testParallelStateWithInitialAndFinalStatesInsideEachRegion() {
        // FinishedStateSample pattern: each region has its own initial and final
        // child states. Initial arrows and final arrows are emitted inside each
        // region block because the regions live inside a parallel parent
        // (insideParallel = true triggers inline emission for those children).
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Composite", childMode = ChildMode.PARALLEL) {
                        state("Region1") {
                            finalState("Done1")
                            initialState("Working1")
                        }
                        state("Region2") {
                            finalState("Done2")
                            initialState("Working2")
                        }
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Composite" as Composite {
                    state "Region1" as Region1 {
                      state "Done1" as Done1
                      state "Working1" as Working1
                      [*] --> Working1
                      Done1 --> [*]
                    }
                    [*] --> Region1
                    --
                    state "Region2" as Region2 {
                      state "Done2" as Done2
                      state "Working2" as Working2
                      [*] --> Working2
                      Done2 --> [*]
                    }
                    [*] --> Region2
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── Transition targeting a parallel state from outside ────────────────────

    fun testTransitionTargetingParallelStateFromSibling() {
        // A transition from a sibling state to the parallel state itself (not to one
        // of its regions). resolveLocalInitializer resolves `work` to the name "Work"
        // via the val binding. The arrow appears in the global pass (Idle's ancestors
        // contain no parallel state). The parallel state's interior is not affected.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val work = state("Work", childMode = ChildMode.PARALLEL) {
                        state("RegionA")
                        state("RegionB")
                    }
                    initialState("Idle") {
                        transition<StartEvent> { targetState = work }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Work" as Work {
                    state "RegionA" as RegionA
                    [*] --> RegionA
                    --
                    state "RegionB" as RegionB
                    [*] --> RegionB
                  }
                  state "Idle" as Idle
                  [*] --> Idle
                }
                Idle --> Work : StartEvent
                """
            ),
        )
    }

    // ── Parallel state nested inside an exclusive parent ─────────────────────

    fun testParallelStateNestedInsideExclusiveParent() {
        // A parallel state declared as a direct child of a non-parallel state.
        // The outer state is rendered as a regular block; its parallel child renders
        // the region layout inside it. Transitions of states that are children of
        // the Outer (non-parallel) state are handled by the global pass — only
        // states inside the Inner parallel block are inlined.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Outer") {
                        state("Inner", childMode = ChildMode.PARALLEL) {
                            state("RegionA")
                            state("RegionB")
                        }
                        initialState("Leaf")
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Outer" as Outer {
                    state "Inner" as Inner {
                      state "RegionA" as RegionA
                      [*] --> RegionA
                      --
                      state "RegionB" as RegionB
                      [*] --> RegionB
                    }
                    state "Leaf" as Leaf
                    [*] --> Leaf
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── Positional ChildMode.PARALLEL argument ────────────────────────────────

    fun testPositionalChildModeParallelArgIsDetectedAsParallel() {
        // `state("P", ChildMode.PARALLEL)` uses a positional argument instead of
        // the named `childMode = ChildMode.PARALLEL` form. isParallelChildMode()
        // accepts any argument whose text ends with ".PARALLEL", so both forms
        // produce an identical parallel rendering.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Work", ChildMode.PARALLEL) {
                        state("RegionA")
                        state("RegionB")
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Work" as Work {
                    state "RegionA" as RegionA
                    [*] --> RegionA
                    --
                    state "RegionB" as RegionB
                    [*] --> RegionB
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── Nested parallel states ────────────────────────────────────────────────

    fun testNestedParallelStatesInsideParallelMachine() {
        // FinishedStateSample pattern with two levels of parallelism:
        // the machine itself is parallel, one of its region children is also
        // parallel (State1), and the other is exclusive (State2).
        // State1's sub-regions (State11 / State12) render as parallel blocks inside
        // State1's block. State2's transition is emitted inline because State2 lives
        // inside the top-level parallel machine.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m", childMode = ChildMode.PARALLEL) {
                    state("State1", childMode = ChildMode.PARALLEL) {
                        state("State11") {
                            finalState("Final111")
                            initialState("Working11")
                        }
                        state("State12") {
                            finalState("Final121")
                            initialState("Working12")
                        }
                    }
                    state("State2") {
                        val finalState22 = finalState("State22")
                        initialState("State21") {
                            transition<SwitchEvent> { targetState = finalState22 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "State1" as State1 {
                    state "State11" as State11 {
                      state "Final111" as Final111
                      state "Working11" as Working11
                      [*] --> Working11
                      Final111 --> [*]
                    }
                    [*] --> State11
                    --
                    state "State12" as State12 {
                      state "Final121" as Final121
                      state "Working12" as Working12
                      [*] --> Working12
                      Final121 --> [*]
                    }
                    [*] --> State12
                  }
                  [*] --> State1
                  --
                  state "State2" as State2 {
                    state "State22" as State22
                    state "State21" as State21
                    State21 --> State22 : SwitchEvent
                    [*] --> State21
                    State22 --> [*]
                  }
                  [*] --> State2
                }
                """
            ),
        )
    }
}