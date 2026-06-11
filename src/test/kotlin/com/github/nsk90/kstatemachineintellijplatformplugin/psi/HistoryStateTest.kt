package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Tests covering the `historyState` / deep-history constructs in KStateMachine.
 *
 * **What the parser does:**
 *   - `historyState("name")` → [StateKind.HISTORY]; `isDeepHistory()` false
 *   - `historyState("name", historyType = HistoryType.DEEP)` or
 *     `historyState("name", HistoryType.DEEP)` → [StateKind.HISTORY_DEEP]
 *   - The `defaultState` argument is parsed but not reflected in the diagram.
 *   - `receiver.historyState("name")` (dot-qualified) is attributed to the
 *     receiver's scope by pre-pass 3 in [PsiElementsParser].
 *
 * **What the generator does (PlantUML mode):**
 *   - History states are NOT declared as `state "…" as …` blocks —
 *     [PlantUmlGenerator] returns early for them in `appendStateDecl`.
 *   - Their diagram ID is `parentId[H]` (shallow) or `parentId[H*]` (deep),
 *     where `parentId` is the ID of the state they were declared inside.
 *   - Transitions that target a history state use the corresponding pseudo-state
 *     notation in the arrow (e.g. `Inner --> Parent[H] : Event`).
 *
 * **Coverage notes:** [PlantUmlGeneratorTest] already covers the canonical
 * shallow- and deep-history (named-arg) patterns. The tests here target the
 * remaining parser corner cases:
 *   - History state declared but never targeted: only its siblings appear.
 *   - Unnamed (`val h = historyState()`) — target resolution goes through the
 *     binding name rather than the DSL name.
 *   - Positional `HistoryType.DEEP` argument (not the named-arg form).
 *   - Receiver-scoped `receiver.historyState("H")` attribution.
 *   - `defaultState` argument present — must not crash the parser.
 *   - Two parent scopes in the same machine, each with its own history state.
 */
class HistoryStateTest : BasePlatformTestCase() {

    // ── Declaration suppression ───────────────────────────────────────────────

    fun testHistoryStateDeclaredButNotTargetedIsJustSuppressed() {
        // A composite state that declares a history state among its children
        // without any transition targeting it. The history state must not appear
        // as a `state "H" as H` declaration in the diagram — it is completely
        // suppressed. The sibling states and the initial arrow are unaffected.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Parent") {
                        historyState("H")
                        initialState("Child1")
                        state("Child2")
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent" as Parent {
                    state "Child1" as Child1
                    state "Child2" as Child2
                    [*] --> Child1
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── Unnamed history state — binding-name resolution ───────────────────────

    fun testUnnamedHistoryStateResolvesViaBindingName() {
        // `val h = historyState()` has no name argument, so the model records
        // `name = "<unnamed>"` and `bindingName = "h"`. A sibling transition
        // `targetState = h` cannot resolve to a string-literal name (historyState()
        // has no name arg), so it falls back to the raw identifier text `"h"`.
        // `resolveTarget("h", …)` then matches the state via its bindingName, and
        // `ids[h]` = "Parent[H]" — the correct PlantUML pseudo-state notation.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val parent = state("Parent") {
                        val h = historyState()
                        val child = initialState("Child") {
                            transition<ResetEvent> { targetState = h }
                        }
                    }
                    initialState("Start") {
                        transition<GoEvent> { targetState = parent }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent" as Parent {
                    state "Child" as Child
                    [*] --> Child
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                Child --> Parent[H] : ResetEvent
                Start --> Parent : GoEvent
                """
            ),
        )
    }

    // ── Deep history — positional HistoryType argument ────────────────────────

    fun testDeepHistoryWithPositionalHistoryTypeArgProducesHStarId() {
        // `historyState("H", HistoryType.DEEP)` uses a positional (unnamed)
        // argument. `isDeepHistory()` accepts any argument whose text ends with
        // `.DEEP`, so `"HistoryType.DEEP"` is detected and the kind is set to
        // HISTORY_DEEP. The ID becomes `Parent[H*]` instead of `Parent[H]`.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val parent = state("Parent") {
                        val h = historyState("H", HistoryType.DEEP)
                        initialState("Child") {
                            transition<ResetEvent> { targetState = h }
                        }
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent" as Parent {
                    state "Child" as Child
                    [*] --> Child
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                Child --> Parent[H*] : ResetEvent
                """
            ),
        )
    }

    // ── Receiver-scoped historyState ──────────────────────────────────────────

    fun testReceiverScopedHistoryStateIsAttributedToReceiverScope() {
        // `val history = parent.historyState("H")` — the dot-qualified form —
        // is collected by pre-pass 3 in parseLambdaChildren and folded into
        // `parent`'s child list, exactly like `val history = historyState("H")`
        // written inside parent's lambda. The binding name "history" is captured
        // from the `val history = …` property declaration (via the
        // KtDotQualifiedExpression path in bindingNameFromAssignment).
        //
        // The transition `targetState = history` resolves through the chain:
        //   history (KtNameRef) → resolveLocalInitializer → parent.historyState("H")
        //   (KtDotQualifiedExpression) → selector historyState("H") → name "H"
        //   → resolveTarget("H") finds the history State → id "Parent[H]".
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val parent = state("Parent") {
                        initialState("Child1")
                        state("Child2")
                    }
                    val history = parent.historyState("H")
                    initialState("Start") {
                        transition<GoBack> { targetState = history }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent" as Parent {
                    state "Child1" as Child1
                    state "Child2" as Child2
                    [*] --> Child1
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                Start --> Parent[H] : GoBack
                """
            ),
        )
    }

    // ── defaultState argument ─────────────────────────────────────────────────

    fun testHistoryStateWithDefaultStateArgDoesNotAffectDiagram() {
        // `historyState("H", defaultState = defaultChild)` — the `defaultState`
        // argument provides a fallback when no history has been recorded yet.
        // The parser ignores it (no model field for it); the history state is
        // still suppressed from the diagram, and siblings render normally.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Parent") {
                        val defaultChild = initialState("Default")
                        historyState("H", defaultState = defaultChild)
                        state("Other")
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent" as Parent {
                    state "Default" as Default
                    state "Other" as Other
                    [*] --> Default
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── Multiple history states in separate parent scopes ─────────────────────

    fun testMultipleHistoryStatesInSeparateScopesGetDistinctPseudoStateIds() {
        // Two sibling composite states, each with its own history state (one
        // shallow, one deep). The states use distinct DSL names ("H1" / "H2")
        // so target resolution is unambiguous. Both must be suppressed as
        // declarations. Their transition targets resolve to distinct IDs:
        // `Parent1[H]` and `Parent2[H*]`. This verifies that `assignIds`
        // correctly prefixes each history state with its own direct parent's ID.
        //
        // Note: if both history states were named "H", `findStateInSubtree`
        // would find the first one (depth-first order) for both transitions.
        // Distinct names avoid that ambiguity and isolate the ID-assignment
        // behaviour under test.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val parent1 = state("Parent1") {
                        val h1 = historyState("H1")
                        initialState("A") {
                            transition<Event1> { targetState = h1 }
                        }
                    }
                    val parent2 = state("Parent2") {
                        val h2 = historyState("H2", historyType = HistoryType.DEEP)
                        initialState("B") {
                            transition<Event2> { targetState = h2 }
                        }
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent1" as Parent1 {
                    state "A" as A
                    [*] --> A
                  }
                  state "Parent2" as Parent2 {
                    state "B" as B
                    [*] --> B
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                A --> Parent1[H] : Event1
                B --> Parent2[H*] : Event2
                """
            ),
        )
    }
}