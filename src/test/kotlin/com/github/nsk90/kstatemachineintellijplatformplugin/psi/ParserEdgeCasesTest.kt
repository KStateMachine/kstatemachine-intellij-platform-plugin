package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtFile

/**
 * Parser edge-case and robustness tests — constructs that each have dedicated parser code paths
 * but were not yet directly exercised by the feature-specific test files.
 *
 * **Machine factory variants:**
 *   - `createStateMachineBlocking(scope, "Name", …)` — scope is the first positional argument,
 *     so `findMachineName` must look for the first *string-literal* positional arg, skipping
 *     the scope. Named `name = "…"` form must also work.
 *   - `createStdLibStateMachine("Name", …)` — no scope argument; name is the first positional.
 *   - Multiple `createStateMachine` calls in the same file are collected independently by
 *     `findTopLevelMachineCalls()` and all returned from `parse()`.
 *
 * **addState plain form:**
 *   - `addState(SomeState())` maps to [StateKind.STATE] via `addStateKindFromCallee()` (no
 *     initial/final arrows), distinct from addInitialState/addFinalState.
 *
 * **String constant resolution (all four paths in `resolveAsLiteral` / `resolvesToStringLiteral`):**
 *   - Local block-scope `val name = "x"` inside a lambda — `findLocalStringConstant`.
 *   - Top-level file property `val NAME = "x"` — `findTopLevelStringConstant`.
 *   - Object member `object Names { val X = "x" }` used as `Names.X` — `findScopedStringConstant`
 *     via the `KtObjectDeclaration` path.
 *   - Companion-object member `class Names { companion object { val X = "x" } }` used as
 *     `Names.X` — same method via the `KtClass.companionObjects` path.
 *
 * **Listener function suppression:**
 *   - Calls whose callee is in [KStateMachineCalls.LISTENER_FUNCTIONS] (`onEntry`, `onExit`,
 *     `onTransitionComplete`, etc.) are explicitly skipped in the main parse pass. Any DSL
 *     factory calls inside their lambda bodies must NOT produce phantom states or transitions.
 *
 * **Top-level object declaration as transition target:**
 *   - `object StandingState : DefaultState()` — `resolveStateNameFromExpr` resolves a bare
 *     identifier to its name via `findObjectDeclarationInFile()` when no local `val/var`
 *     binding is found.
 *
 * **`transitionOn {}` with empty body:**
 *   - `transitionOn` with no `targetState = { … }` assignment produces an empty `targetGroups`
 *     list. Unlike `transition` (which has `supportsTargetlessSemantics = true`), `transitionOn`
 *     does NOT emit a self-loop — the transition is silently absent from the diagram.
 */
class ParserEdgeCasesTest : BasePlatformTestCase() {

    // ── Machine factory variants ───────────────────────────────────────────────

    fun testCreateStateMachineBlockingExtractsNameFromSecondPositionalArg() {
        // createStateMachineBlocking(scope, "Name", …) — the scope object is the
        // first positional arg but is not a string literal, so findMachineName
        // advances to the second positional arg ("Hero") which is a string literal.
        assertPlantUml(
            source = """
                val machine = createStateMachineBlocking(coroutineScope, "Hero") {
                    initialState("Idle")
                }
            """,
            expected = bodyTags(
                """
                state "Hero" as Hero {
                  state "Idle" as Idle
                  [*] --> Idle
                }
                """
            ),
        )
    }

    fun testCreateStateMachineBlockingExtractsNameFromNamedArg() {
        // Named `name = "Hero"` — the explicit name argument is found in priority
        // before any positional string-literal search.
        assertPlantUml(
            source = """
                val machine = createStateMachineBlocking(scope, name = "Hero") {
                    initialState("Idle")
                }
            """,
            expected = bodyTags(
                """
                state "Hero" as Hero {
                  state "Idle" as Idle
                  [*] --> Idle
                }
                """
            ),
        )
    }

    fun testCreateStdLibStateMachineExtractsNameFromFirstStringLiteralArg() {
        // createStdLibStateMachine("Hero") — no CoroutineScope argument; the name
        // is the first (and only) positional string-literal argument.
        assertPlantUml(
            source = """
                val machine = createStdLibStateMachine("Hero") {
                    initialState("Idle")
                }
            """,
            expected = bodyTags(
                """
                state "Hero" as Hero {
                  state "Idle" as Idle
                  [*] --> Idle
                }
                """
            ),
        )
    }

    fun testMultipleTopLevelMachinesInSameFileParsedAsDistinctEntries() {
        // findTopLevelMachineCalls() collects every top-level createStateMachine call
        // that is not nested inside another machine. Two machines in the same file
        // must produce two independent entries in the parse result.
        val machines = parseMachines(
            source = """
                val first = createStateMachine("First") {
                    initialState("A")
                }
                val second = createStateMachine("Second") {
                    initialState("B")
                }
            """
        )
        machines.size shouldBe 2
        machines[0].name.trim('"') shouldBe "First"
        machines[1].name.trim('"') shouldBe "Second"
    }

    // ── Plain addState ─────────────────────────────────────────────────────────

    fun testPlainAddStateRendersAsRegularStateWithNoInitialOrFinalArrows() {
        // addState(X()) maps to StateKind.STATE via addStateKindFromCallee() — neither
        // isInitial() nor isFinal() applies, so no [*] --> or --> [*] arrows are emitted.
        // addInitialState is present to give the machine a valid initial arrow so the
        // contrast between the two forms is clear.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    addState(IdleState())
                    addInitialState(StartState())
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "IdleState" as IdleState
                  state "StartState" as StartState
                  [*] --> StartState
                }
                """
            ),
        )
    }

    // ── String constant resolution ─────────────────────────────────────────────

    fun testLocalBlockScopeStringConstantResolvesToStateName() {
        // `val name = "red"` declared inside the machine lambda (a KtBlockExpression)
        // — findLocalStringConstant walks enclosing block scopes to find the binding.
        // The state's display name and id both become "red".
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val name = "red"
                    initialState(name)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "red" as red
                  [*] --> red
                }
                """
            ),
        )
    }

    fun testTopLevelFileStringConstantResolvesToStateName() {
        // Top-level `val STATE_NAME = "idle"` — findTopLevelStringConstant looks at
        // the KtFile's top-level property declarations. The reference `STATE_NAME`
        // inside the machine lambda resolves to "idle" via this path.
        assertPlantUml(
            source = """
                val STATE_NAME = "idle"

                val machine = createStateMachine("m") {
                    initialState(STATE_NAME)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "idle" as idle
                  [*] --> idle
                }
                """
            ),
        )
    }

    fun testObjectMemberStringConstantResolvesToStateName() {
        // `object StateNames { val IDLE = "idle" }` — findScopedStringConstant finds
        // a KtObjectDeclaration named "StateNames" in the file, then locates property
        // "IDLE" within it. The dotted expression `StateNames.IDLE` resolves to "idle".
        assertPlantUml(
            source = """
                object StateNames {
                    val IDLE = "idle"
                }

                val machine = createStateMachine("m") {
                    initialState(StateNames.IDLE)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "idle" as idle
                  [*] --> idle
                }
                """
            ),
        )
    }

    fun testCompanionObjectStringConstantResolvesToStateName() {
        // `class StateNames { companion object { val IDLE = "idle" } }` —
        // findScopedStringConstant falls through to the KtClass path and locates
        // the companion object's property "IDLE". The dotted `StateNames.IDLE`
        // expression resolves to "idle" through the same mechanism.
        assertPlantUml(
            source = """
                class StateNames {
                    companion object {
                        val IDLE = "idle"
                    }
                }

                val machine = createStateMachine("m") {
                    initialState(StateNames.IDLE)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "idle" as idle
                  [*] --> idle
                }
                """
            ),
        )
    }

    // ── Listener function suppression ──────────────────────────────────────────

    fun testStatesInsideListenerBodyAreNotParsedAsRealStates() {
        // `onEntry { state("ghost") }` — "onEntry" is in LISTENER_FUNCTIONS, so the
        // parser's main pass returns early on the call instead of recursing into its
        // lambda. The `state("ghost")` call inside the listener body must NOT produce
        // a phantom state node in the diagram.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialState("Start") {
                        onEntry {
                            state("ghost")
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    fun testTransitionsInsideListenerBodyAreNotParsedAsRealTransitions() {
        // `onTransitionComplete { transition<E> { … } }` — the `onTransitionComplete`
        // callee is in LISTENER_FUNCTIONS. The `transition<E>` inside its lambda body
        // is NOT parsed as a real DSL transition and must NOT produce an arrow in the
        // diagram.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val s2 = state("S2")
                    initialState("S1") {
                        onTransitionComplete {
                            transition<GoEvent> { targetState = s2 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S2" as S2
                  state "S1" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }

    // ── Object declaration as transition target ────────────────────────────────

    fun testTopLevelObjectDeclarationResolvesAsTransitionTarget() {
        // `object StandingState` declared at top level — when used as `targetState =
        // StandingState` in a transition, resolveStateNameFromExpr calls
        // resolveLocalInitializer() (finds nothing — it's not a val/var), then
        // findObjectDeclarationInFile() which scans the file's top-level declarations
        // and returns the object's own name "StandingState".
        // addState(StandingState) registers the same name via simplifiedStateName().
        assertPlantUml(
            source = """
                object StandingState

                val machine = createStateMachine("m") {
                    initialState("Walking") {
                        transition<StopEvent>(targetState = StandingState)
                    }
                    addState(StandingState)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Walking" as Walking
                  state "StandingState" as StandingState
                  [*] --> Walking
                }
                Walking --> StandingState : StopEvent
                """
            ),
        )
    }

    // ── transitionOn with empty / targetless body ──────────────────────────────

    fun testTransitionOnWithEmptyBodyEmitsNoArrow() {
        // `transitionOn<E> { }` with no `targetState = { … }` assignment:
        // findTargetGroups returns emptyList because findLambdaAssignmentEntry finds
        // no TARGET_STATE_PROPERTY entry. Unlike `transition {}` (which uses
        // supportsTargetlessSemantics = true and produces a self-loop), `transitionOn`
        // is NOT in that list, so the generator silently skips this transition.
        // The state S2 still appears (it is declared in the machine), but no arrow
        // is drawn from S1 to S2.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val s2 = state("S2")
                    initialState("S1") {
                        transitionOn<Event> { }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S2" as S2
                  state "S1" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }

    fun testTransitionWithEmptyBodyRendersSelfLoop() {
        // `transition<E> { }` with an empty lambda — unlike `transitionOn`, the
        // callee "transition" satisfies supportsTargetlessSemantics(). With no
        // `targetState` assignment the targetGroups list is empty, so a self-loop
        // `S1 --> S1 : E` is emitted. This is the same semantics as
        // `transition<E>()` (no lambda at all), confirming both forms are equivalent
        // from the parser/generator perspective.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        transition<PingEvent> { }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S1" as S1
                  [*] --> S1
                }
                S1 --> S1 : PingEvent
                """
            ),
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun assertPlantUml(source: String, expected: String) {
        val rendered = PlantUmlGenerator.render(parseSingleMachine(source)).trim()
        rendered shouldBe expected.trimIndent().trim()
    }

    private fun parseSingleMachine(source: String): StateMachine {
        val machines = parseMachines(source)
        require(machines.size == 1) {
            "Expected exactly one state machine in source, got ${machines.size}"
        }
        return machines.single()
    }

    private fun parseMachines(source: String): List<StateMachine> {
        val file = myFixture.configureByText("Test.kt", source.trimIndent()) as KtFile
        return PsiElementsParser { }.parse(file)
    }

    private fun bodyTags(expected: String) = """
        @startuml
        top to bottom direction
        hide empty description

${expected.trimIndent().trim().prependIndent("        ")}
        @enduml
    """.trimIndent()
}
