package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Tests covering `choiceState`, `initialChoiceState`, `choiceDataState`, and
 * `initialChoiceDataState` constructs in KStateMachine.
 *
 * **What the parser does:**
 *   - All four variants map to [StateKind] values in the CHOICE family
 *     (`CHOICE`, `INITIAL_CHOICE`, `CHOICE_DATA`, `INITIAL_CHOICE_DATA`).
 *   - `redirectTargets` are extracted from the trailing lambda via
 *     `findChoiceRedirectTargets` → `extractDirectTargets`, which walks
 *     if/else and when branches collecting every statically-resolvable state
 *     reference. Opaque call expressions produce no targets.
 *   - Unnamed choice states (`val c = choiceState { … }`) record the Kotlin
 *     variable name as `bindingName`; target resolution and ID assignment
 *     both fall back to this name when the DSL name is absent.
 *   - Choice states nested inside a parent state can reference siblings of
 *     their parent via `resolveLocalInitializer` walking outward to the
 *     machine's lambda block.
 *
 * **What the generator does (PlantUML mode):**
 *   - The `<<choice>>` stereotype is applied: the declaration header is
 *     `state <id> <<choice>>` (bare ID, no display-name alias).
 *   - Redirect arrows (`id --> targetId`) are emitted in the global
 *     transition pass via `appendChoiceRedirect`; they carry no event label.
 *   - `INITIAL_CHOICE` / `INITIAL_CHOICE_DATA` states also emit `[*] --> id`
 *     inside their enclosing block, because `isInitial()` returns true for them.
 *   - `dataType` from `choiceDataState<T>` / `initialChoiceDataState<T>` is
 *     recorded in the model but not rendered in the diagram (no generator
 *     support for it yet); the stereotype and redirect arrows still appear.
 */
class ChoiceStateTest : BasePlatformTestCase() {

    // ── Basic choiceState ─────────────────────────────────────────────────────

    fun testChoiceStateWithIfElseBodyRendersChoiceStereotypeAndRedirects() {
        // Canonical choice-state pattern: two possible targets from an if/else
        // body. The generator emits `state choice <<choice>>` (not a named-alias
        // block), then `choice --> StateA` and `choice --> StateB` redirect
        // arrows in the global pass. The transition FROM Start TO the choice
        // state also renders normally.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    val choice = choiceState {
                        if (condition) stateA else stateB
                    }
                    initialState("Start") {
                        transition<GoEvent> { targetState = choice }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state choice <<choice>>
                  state "Start" as Start
                  [*] --> Start
                }
                choice --> StateA
                choice --> StateB
                Start --> choice : GoEvent
                """
            ),
        )
    }

    fun testNamedChoiceStateUsesNameAsId() {
        // `choiceState("Decide") { … }` supplies an explicit DSL name. The
        // generator uses "Decide" as the diagram ID (sanitizeId strips nothing
        // here). The declaration is `state Decide <<choice>>` — no
        // `"display" as id` alias because stereotyped states don't need one.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    choiceState("Decide") {
                        if (condition) stateA else stateB
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state Decide <<choice>>
                  state "Start" as Start
                  [*] --> Start
                }
                Decide --> StateA
                Decide --> StateB
                """
            ),
        )
    }

    fun testUnnamedChoiceStateUsesBindingNameAsId() {
        // `val myChoice = choiceState { stateA }` has no DSL name, so the
        // parser records `name = "<unnamed>"` and `bindingName = "myChoice"`.
        // `preferredLabel()` returns the binding name, giving the diagram ID
        // "myChoice". Transition target resolution for `targetState = myChoice`
        // falls back to the raw text "myChoice", which `resolveTarget` matches
        // against the state's `bindingName`.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val myChoice = choiceState {
                        stateA
                    }
                    initialState("Start") {
                        transition<GoEvent> { targetState = myChoice }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state myChoice <<choice>>
                  state "Start" as Start
                  [*] --> Start
                }
                myChoice --> StateA
                Start --> myChoice : GoEvent
                """
            ),
        )
    }

    // ── initialChoiceState ────────────────────────────────────────────────────

    fun testInitialChoiceStateGetsInitialArrowAndChoiceRedirects() {
        // `initialChoiceState("Route")` satisfies both `isInitial()` and
        // `isChoice()`. The generator therefore emits:
        //   - `[*] --> Route` inside the machine block (initial entry arrow).
        //   - `Route --> StateA` and `Route --> StateB` in the global pass
        //     (choice redirect arrows).
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    initialChoiceState("Route") {
                        if (condition) stateA else stateB
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state Route <<choice>>
                  [*] --> Route
                }
                Route --> StateA
                Route --> StateB
                """
            ),
        )
    }

    // ── choiceDataState / initialChoiceDataState ──────────────────────────────

    fun testChoiceDataStateRendersIdenticallyToChoiceState() {
        // `choiceDataState<MyData>` carries a data type argument, but the
        // PlantUML generator does not render it. The diagram is identical to
        // a plain `choiceState` — `<<choice>>` stereotype and redirect arrows —
        // confirming that the parser correctly classifies it as a CHOICE_DATA
        // kind (which passes `isChoice()`) and does not confuse it with a
        // DATA state that lacks the choice stereotype.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    val choice = choiceDataState<MyData> {
                        if (condition) stateA else stateB
                    }
                    initialState("Start") {
                        transition<GoEvent> { targetState = choice }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state choice <<choice>>
                  state "Start" as Start
                  [*] --> Start
                }
                choice --> StateA
                choice --> StateB
                Start --> choice : GoEvent
                """
            ),
        )
    }

    fun testInitialChoiceDataStateGetsInitialArrowAndChoiceRedirects() {
        // `initialChoiceDataState<T>("Route")` is INITIAL_CHOICE_DATA, which
        // satisfies both `isInitial()` and `isChoice()`. Same dual-arrow output
        // as `initialChoiceState`, with the data type argument silently ignored
        // by the diagram renderer.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    initialChoiceDataState<MyData>("Route") {
                        if (condition) stateA else stateB
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state Route <<choice>>
                  [*] --> Route
                }
                Route --> StateA
                Route --> StateB
                """
            ),
        )
    }

    // ── when expression in choice body ────────────────────────────────────────

    fun testChoiceStateWhenExpressionProducesOneRedirectArrowPerBranch() {
        // `extractDirectTargets` handles `KtWhenExpression` by visiting each
        // entry's body expression in turn. A three-branch when produces three
        // redirect arrows, one per resolved target, in source order.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val stateA = state("StateA")
                    val stateB = state("StateB")
                    val stateC = state("StateC")
                    val choice = choiceState {
                        when (x) {
                            1 -> stateA
                            2 -> stateB
                            else -> stateC
                        }
                    }
                    initialState("Start") {
                        transition<GoEvent> { targetState = choice }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "StateA" as StateA
                  state "StateB" as StateB
                  state "StateC" as StateC
                  state choice <<choice>>
                  state "Start" as Start
                  [*] --> Start
                }
                choice --> StateA
                choice --> StateB
                choice --> StateC
                Start --> choice : GoEvent
                """
            ),
        )
    }

    // ── opaque body — no static targets ──────────────────────────────────────

    fun testChoiceStateWithOpaqueBodyProducesNoRedirectArrows() {
        // When the choice lambda body returns the result of an opaque function
        // call (`selectNextState()`), `extractDirectTargets` cannot resolve any
        // static target: `KtCallExpression` is not in the resolvable subset and
        // `targetFallbackText()` returns null for call expressions. The state
        // is still rendered as `<<choice>>`, but no redirect arrows are emitted.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val choice = choiceState {
                        selectNextState()
                    }
                    initialState("Start") {
                        transition<GoEvent> { targetState = choice }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state choice <<choice>>
                  state "Start" as Start
                  [*] --> Start
                }
                Start --> choice : GoEvent
                """
            ),
        )
    }

    // ── choice state nested inside a parent ───────────────────────────────────

    fun testChoiceStateNestedInsideParentRedirectsResolveAcrossScopes() {
        // A choice state declared inside `Parent`'s lambda whose if/else body
        // references one sibling (`Inner`, declared in the same lambda) and one
        // state from the outer machine scope (`Outer`). `extractDirectTargets`
        // walks up through `Parent`'s block for `inner` and continues up through
        // the machine's block for `outer`. Both resolve correctly because
        // `findStateInSubtree` starts from the outermost ancestor (machine) and
        // can reach any state in the tree.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val outer = state("Outer")
                    initialState("Parent") {
                        val inner = state("Inner")
                        val choice = choiceState {
                            if (condition) inner else outer
                        }
                        transition<GoEvent> { targetState = choice }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Outer" as Outer
                  state "Parent" as Parent {
                    state "Inner" as Inner
                    state choice <<choice>>
                  }
                  [*] --> Parent
                }
                Parent --> choice : GoEvent
                choice --> Inner
                choice --> Outer
                """
            ),
        )
    }
}