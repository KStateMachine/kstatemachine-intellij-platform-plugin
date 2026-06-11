package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Tests covering the fork/join parallel-control-flow constructs in KStateMachine:
 *
 * **Fork** — `transitionConditionally { direction = { targetParallelStates(A, B, …) } }` —
 * enters multiple states simultaneously. The parser produces a [TargetGroup] with
 * `isParallel = true`; the generator emits a `<<fork>>` pseudo-state declaration inside
 * the source's enclosing block plus a source→fork arrow (labeled with the event) and
 * unlabeled fork→target arrows.
 *
 * **Join** — `joinTransition(A, B, …, targetState = X)` /
 * `joinDataTransition(A, B, …, targetState = X)` — synchronises multiple parallel states
 * back into one. The parser captures positional arguments as [Transition.joinSources] and
 * the named `targetState` arg via normal target resolution. The generator emits a `<<join>>`
 * pseudo-state declaration *inside* the containing state's block (only reachable when that
 * state has child states, so the block exists), plus individual source→join arrows and a
 * single join→target arrow.
 *
 * **Coverage beyond [PlantUmlGeneratorTest]:**
 * - Fork into 3+ parallel targets (existing tests only exercise 2-target forks).
 * - Mixed `targetParallelStates` + `targetState` branches in the same direction lambda.
 * - `joinTransition` with three sources.
 * - `joinTransition` on a *non-parallel* parent state that still has children (verifies
 *   that `<<join>>` placement is not gated on `ChildMode.PARALLEL`).
 * - Join source names that cannot be resolved to states in the file — fallback to raw
 *   identifier text and `sanitizeId` passthrough.
 * - Complete fork → parallel-work → join cycle in one machine.
 */
class ForkAndJoinTest : BasePlatformTestCase() {

    // ── Fork ─────────────────────────────────────────────────────────────────

    fun testForkIntoThreeParallelTargets() {
        // targetParallelStates(A, B, C) with three targets produces one <<fork>>
        // pseudo-state and three outgoing arrows — one per target.  The fork
        // declaration is placed inside the enclosing machine block by
        // appendForkDeclarations; the arrows are emitted by the global transition
        // pass.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    val stateC = state("StateC")
                    initialState("Start") {
                        transitionConditionally<SwitchEvent> {
                            direction = { targetParallelStates(stateA, stateB, stateC) }
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
                  state "Start" as Start
                  [*] --> Start
                  state fork_Start_0 <<fork>>
                }
                Start --> fork_Start_0 : SwitchEvent
                fork_Start_0 --> StateA
                fork_Start_0 --> StateB
                fork_Start_0 --> StateC
                """
            ),
        )
    }

    fun testForkMixedWithRegularTargetInConditionalDirection() {
        // A direction lambda that contains both `targetParallelStates` (for the
        // fork group) and `targetState` (for a single-target group) in separate
        // if/else branches.  extractConditionalGroups collects both; the generator
        // emits fork arrows for the parallel group (group index 0) and a plain
        // arrow for the single-target group (group index 1), both labeled with
        // the transition event.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    val stateC = state("StateC")
                    initialState("Start") {
                        transitionConditionally<SwitchEvent> {
                            direction = {
                                if (condition) targetParallelStates(stateA, stateB)
                                else targetState(stateC)
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
                  state "Start" as Start
                  [*] --> Start
                  state fork_Start_0 <<fork>>
                }
                Start --> fork_Start_0 : SwitchEvent
                fork_Start_0 --> StateA
                fork_Start_0 --> StateB
                Start --> StateC : SwitchEvent
                """
            ),
        )
    }

    // ── Join ─────────────────────────────────────────────────────────────────

    fun testJoinTransitionWithThreeSources() {
        // joinTransition with three join-point sources produces three
        // source→join arrows; the <<join>> declaration appears inside the
        // enclosing parallel block.  Join-point states (jpA/jpB/jpC) are
        // declared inside their respective regions, so resolveLocalInitializer
        // cannot reach them from the joinTransition call site — the parser falls
        // back to raw identifier text ("jpA" etc.), and the renderer resolves
        // those raw names against the full machine subtree at render time.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val afterJoin = state("AfterJoin")
                    initialState("parallelWork", childMode = ChildMode.PARALLEL) {
                        state("regionA") {
                            val jpA = state("jpA")
                            initialState("workA") {
                                transition<EventA> { targetState = jpA }
                            }
                        }
                        state("regionB") {
                            val jpB = state("jpB")
                            initialState("workB") {
                                transition<EventB> { targetState = jpB }
                            }
                        }
                        state("regionC") {
                            val jpC = state("jpC")
                            initialState("workC") {
                                transition<EventC> { targetState = jpC }
                            }
                        }
                        joinTransition(jpA, jpB, jpC, targetState = afterJoin)
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "AfterJoin" as AfterJoin
                  state "parallelWork" as parallelWork {
                    state "regionA" as regionA {
                      state "jpA" as jpA
                      state "workA" as workA
                      workA --> jpA : EventA
                      [*] --> workA
                    }
                    [*] --> regionA
                    --
                    state "regionB" as regionB {
                      state "jpB" as jpB
                      state "workB" as workB
                      workB --> jpB : EventB
                      [*] --> workB
                    }
                    [*] --> regionB
                    --
                    state "regionC" as regionC {
                      state "jpC" as jpC
                      state "workC" as workC
                      workC --> jpC : EventC
                      [*] --> workC
                    }
                    [*] --> regionC
                    state join_parallelWork_0 <<join>>
                  }
                  [*] --> parallelWork
                }
                jpA --> join_parallelWork_0
                jpB --> join_parallelWork_0
                jpC --> join_parallelWork_0
                join_parallelWork_0 --> AfterJoin
                """
            ),
        )
    }

    fun testJoinTransitionOnNonParallelParentDeclaresJoinInsideBlock() {
        // joinTransition declared on a *non-parallel* state that still has child
        // states.  appendJoinDeclarations is called for any state with children
        // (both the isParallel and the regular-with-children branches), so the
        // <<join>> declaration is emitted inside the container's block regardless
        // of childMode.  The join source names are resolved via the container's
        // own lambda scope.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val joined = state("Joined")
                    state("Container") {
                        val inner1 = initialState("Inner1")
                        val inner2 = state("Inner2")
                        joinTransition(inner1, inner2, targetState = joined)
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "Joined" as Joined
                  state "Container" as Container {
                    state "Inner1" as Inner1
                    state "Inner2" as Inner2
                    [*] --> Inner1
                    state join_Container_0 <<join>>
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                Inner1 --> join_Container_0
                Inner2 --> join_Container_0
                join_Container_0 --> Joined
                """
            ),
        )
    }

    fun testJoinTransitionWithUnresolvableSourcesFallsBackToRawText() {
        // When join-source identifiers cannot be resolved to any state in the
        // file (no matching val, var, or object declaration), the parser falls
        // back to the raw identifier text via targetFallbackText().  The renderer
        // passes those strings through sanitizeId() and emits phantom source ids
        // that don't correspond to any declared state — the <<join>> declaration
        // and target arrow are still emitted correctly.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val joined = state("Joined")
                    state("Parallel", childMode = ChildMode.PARALLEL) {
                        initialState("RegionA")
                        state("RegionB")
                        joinTransition(extState1, extState2, targetState = joined)
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "Joined" as Joined
                  state "Parallel" as Parallel {
                    state "RegionA" as RegionA
                    [*] --> RegionA
                    --
                    state "RegionB" as RegionB
                    [*] --> RegionB
                    state join_Parallel_0 <<join>>
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                extState1 --> join_Parallel_0
                extState2 --> join_Parallel_0
                join_Parallel_0 --> Joined
                """
            ),
        )
    }

    // ── Fork + Join combined ─────────────────────────────────────────────────

    fun testForkAndJoinCompletePattern() {
        // Full fork → parallel-work → join cycle:
        //   1. Start forks via targetParallelStates(RegionA, RegionB).
        //   2. ParallelWork hosts RegionA and RegionB as parallel children
        //      (added via addInitialState / addState so the identifiers are
        //      accessible in Start's scope without inner-lambda scoping issues).
        //   3. joinTransition(RegionA, RegionB, targetState = Final) joins back.
        //
        // Traversal order in the global pass is depth-first pre-order, so
        // ParallelWork's join arrows appear before Start's fork arrows in the
        // output.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val final = state("Final")
                    state("ParallelWork", childMode = ChildMode.PARALLEL) {
                        addInitialState(RegionA)
                        addState(RegionB)
                        joinTransition(RegionA, RegionB, targetState = final)
                    }
                    initialState("Start") {
                        transitionConditionally<GoEvent> {
                            direction = { targetParallelStates(RegionA, RegionB) }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "Final" as Final
                  state "ParallelWork" as ParallelWork {
                    state "RegionA" as RegionA
                    [*] --> RegionA
                    --
                    state "RegionB" as RegionB
                    [*] --> RegionB
                    state join_ParallelWork_0 <<join>>
                  }
                  state "Start" as Start
                  [*] --> Start
                  state fork_Start_0 <<fork>>
                }
                RegionA --> join_ParallelWork_0
                RegionB --> join_ParallelWork_0
                join_ParallelWork_0 --> Final
                Start --> fork_Start_0 : GoEvent
                fork_Start_0 --> RegionA
                fork_Start_0 --> RegionB
                """
            ),
        )
    }
}