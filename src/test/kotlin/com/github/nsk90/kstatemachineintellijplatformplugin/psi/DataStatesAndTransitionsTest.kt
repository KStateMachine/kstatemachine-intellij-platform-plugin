package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtFile

/**
 * Tests covering data states and data transitions (typesafe transitions) in KStateMachine.
 *
 * Data states carry typed payload; data transitions require a matching DataEvent. The plugin
 * records the type argument in the model but does NOT render it in the diagram — data states
 * appear as plain nodes, differentiated only by their initial/final kind arrows.
 *
 * **State factory coverage:**
 *   - `dataState<D>` → [StateKind.DATA] — plain node, no entry/exit arrows
 *   - `initialDataState<D>` → [StateKind.INITIAL_DATA] — emits `[*] --> id`
 *   - `finalDataState<D>` → [StateKind.FINAL_DATA] — emits `id --> [*]`
 *   - `initialFinalDataState<D>` → [StateKind.INITIAL_FINAL_DATA] — both arrows
 *   - `mutableDataState<D>` → [StateKind.MUTABLE_DATA] — plain node
 *   - `initialMutableDataState<D>` → [StateKind.INITIAL_MUTABLE_DATA] — emits `[*] --> id`
 *   - `finalMutableDataState<D>` → [StateKind.FINAL_MUTABLE_DATA] — emits `id --> [*]`
 *   - `initialFinalMutableDataState<D>` → [StateKind.INITIAL_FINAL_MUTABLE_DATA] — both arrows
 *
 * **Transition factory coverage:**
 *   - `dataTransition<E, D>` — rendered as a regular arrow labeled with E; D is not shown;
 *     no `targetState` → self-loop (supportsTargetlessSemantics = true for this callee)
 *   - `dataTransitionOn<E, D>` — like `transitionOn`, lambda `targetState = { … }` resolved
 *     via extractDirectTargets; branching body produces multiple arrows
 *
 * **Corner cases:**
 *   - `defaultData` argument (named or positional) is parsed but does not alter the diagram
 *   - Data states mix freely with plain states in the same machine
 *   - Chained data transitions across multiple data states
 *
 * Source samples are drawn from TypesafeTransitionSample, MutableDataStateSample, and
 * DataStateTest / TypesafeTransitionTest in the KStateMachine library.
 */
class DataStatesAndTransitionsTest : BasePlatformTestCase() {

    // ── dataState<D> ──────────────────────────────────────────────────────────

    fun testDataStateRendersAsPlainStateWithNoArrows() {
        // dataState<D> is StateKind.DATA: neither isInitial() nor isFinal() applies,
        // so only the state declaration is emitted — no [*] --> or --> [*] arrows.
        // The type argument D ("LoginData") is invisible in the diagram.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val accountForm = dataState<LoginData>("accountForm")
                    initialState("loginForm")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "accountForm" as accountForm
                  state "loginForm" as loginForm
                  [*] --> loginForm
                }
                """
            ),
        )
    }

    fun testDataStateTargetedByDataTransitionArrowLabeledWithEventType() {
        // TypesafeTransitionSample pattern: initialState holds a dataTransition whose
        // target is a dataState. The arrow label is the event type E ("LoginEvent");
        // the data type D ("LoginData") is recorded in the model but not rendered.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val accountForm = dataState<LoginData>("accountForm")
                    initialState("loginForm") {
                        dataTransition<LoginEvent, LoginData> {
                            targetState = accountForm
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "accountForm" as accountForm
                  state "loginForm" as loginForm
                  [*] --> loginForm
                }
                loginForm --> accountForm : LoginEvent
                """
            ),
        )
    }

    // ── initialDataState<D> ───────────────────────────────────────────────────

    fun testInitialDataStateGetsInitialArrow() {
        // initialDataState<D> is StateKind.INITIAL_DATA, satisfying isInitial() —
        // the generator emits [*] --> id inside the enclosing block, same as
        // plain initialState.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialDataState<String>("state1", defaultData = "hello")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "state1" as state1
                  [*] --> state1
                }
                """
            ),
        )
    }

    fun testInitialDataStateWithDefaultDataDoesNotAffectDiagram() {
        // The `defaultData` named arg is parsed into State.defaultData but the
        // generator ignores it. Diagram output is identical to initialDataState
        // without defaultData — only the initial arrow and any transitions show.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val s2 = state("s2")
                    initialDataState<Int>("s1", defaultData = 42) {
                        transition<GoEvent> { targetState = s2 }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "s2" as s2
                  state "s1" as s1
                  [*] --> s1
                }
                s1 --> s2 : GoEvent
                """
            ),
        )
    }

    // ── finalDataState<D> ─────────────────────────────────────────────────────

    fun testFinalDataStateGetsFinalArrow() {
        // finalDataState<D> is StateKind.FINAL_DATA, satisfying isFinal() —
        // the generator emits id --> [*]. A dataTransition carries the arrow into it.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val final = finalDataState<Int>("final")
                    initialState("initial") {
                        dataTransition<IdEvent, Int> { targetState = final }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "final" as final
                  state "initial" as initial
                  [*] --> initial
                  final --> [*]
                }
                initial --> final : IdEvent
                """
            ),
        )
    }

    // ── initialFinalDataState<D> ──────────────────────────────────────────────

    fun testInitialFinalDataStateGetsBothArrows() {
        // initialFinalDataState<D> is StateKind.INITIAL_FINAL_DATA, satisfying both
        // isInitial() and isFinal() — the generator emits [*] --> id AND id --> [*]
        // for the same state node, mirroring the plain initialFinalState behaviour.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialFinalDataState<Int>("terminal", defaultData = 1)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "terminal" as terminal
                  [*] --> terminal
                  terminal --> [*]
                }
                """
            ),
        )
    }

    // ── mutableDataState<D> ───────────────────────────────────────────────────

    fun testMutableDataStateRendersAsPlainStateWithNoArrows() {
        // mutableDataState<D> is StateKind.MUTABLE_DATA — no initial/final arrows,
        // same rendering as a plain state() node. Its transitions still appear.
        // Note: "start" and "end" are reserved ids in assignIds, so state names
        // that collide with them would get a "_2" suffix — we use "idle" here.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val done = finalState("done")
                    mutableDataState<Int>("counter") {
                        transition<SwitchEvent> { targetState = done }
                    }
                    initialState("idle")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "done" as done
                  state "counter" as counter
                  state "idle" as idle
                  [*] --> idle
                  done --> [*]
                }
                counter --> done : SwitchEvent
                """
            ),
        )
    }

    fun testInitialMutableDataStateWithTargetlessAndExitTransitions() {
        // MutableDataStateSample pattern: internal targetless transitions (no targetState
        // assignment) render as self-loops because callee "transition" satisfies
        // supportsTargetlessSemantics(). The exit transition to done renders normally.
        // defaultData = 0 is recorded in the model but does not appear in the diagram.
        assertPlantUml(
            source = """
                val machine = createStateMachine {
                    val done = finalState("done")
                    val counter = initialMutableDataState<Int>("counter", defaultData = 0) {
                        transition<IncrementEvent>()
                        transition<ResetEvent>()
                        transition<SwitchEvent> { targetState = done }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "done" as done
                  state "counter" as counter
                  [*] --> counter
                  done --> [*]
                }
                counter --> counter : IncrementEvent
                counter --> counter : ResetEvent
                counter --> done : SwitchEvent
                """
            ),
        )
    }

    fun testFinalMutableDataStateGetsFinalArrow() {
        // finalMutableDataState<D> is StateKind.FINAL_MUTABLE_DATA, satisfying isFinal().
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val result = finalMutableDataState<String>("result")
                    initialState("working") {
                        transition<DoneEvent> { targetState = result }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "result" as result
                  state "working" as working
                  [*] --> working
                  result --> [*]
                }
                working --> result : DoneEvent
                """
            ),
        )
    }

    fun testInitialFinalMutableDataStateGetsBothArrows() {
        // initialFinalMutableDataState<D> is StateKind.INITIAL_FINAL_MUTABLE_DATA,
        // satisfying both isInitial() and isFinal() — both arrows are emitted.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialFinalMutableDataState<Int>("result", defaultData = 0)
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "result" as result
                  [*] --> result
                  result --> [*]
                }
                """
            ),
        )
    }

    // ── dataTransition<E, D> ──────────────────────────────────────────────────

    fun testDataTransitionWithoutTargetRendersSelfLoop() {
        // dataTransition<E, D>() with no targetState — callee "dataTransition"
        // satisfies supportsTargetlessSemantics(), so the generator emits
        // Source --> Source : EventType (a self-loop). Data type D is not shown.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    initialState("state1") {
                        dataTransition<UpdateEvent, Payload>()
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "state1" as state1
                  [*] --> state1
                }
                state1 --> state1 : UpdateEvent
                """
            ),
        )
    }

    // ── dataTransitionOn<E, D> ────────────────────────────────────────────────

    fun testDataTransitionOnWithLambdaTargetRendersArrow() {
        // dataTransitionOn<E, D> uses a lambda for targetState (same structure as
        // transitionOn). extractDirectTargets resolves the single-expression body
        // to the target state. From TypesafeTransitionTest "FinalDataState transition".
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val final = finalDataState<Int>("final")
                    initialState("initial") {
                        dataTransitionOn<IdEvent, Int> { targetState = { final } }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "final" as final
                  state "initial" as initial
                  [*] --> initial
                  final --> [*]
                }
                initial --> final : IdEvent
                """
            ),
        )
    }

    fun testDataTransitionOnWithIfElseBranchingRendersTwoArrows() {
        // extractDirectTargets walks the if/else branches of the dataTransitionOn
        // lambda and produces one TargetGroup per resolved branch — two arrows
        // from state1, both labeled with the event type.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val state2 = dataState<String>("state2")
                    val state3 = dataState<Int>("state3")
                    initialState("state1") {
                        dataTransitionOn<NameEvent, String> {
                            targetState = { if (condition) state2 else state3 }
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "state2" as state2
                  state "state3" as state3
                  state "state1" as state1
                  [*] --> state1
                }
                state1 --> state2 : NameEvent
                state1 --> state3 : NameEvent
                """
            ),
        )
    }

    // ── Chained data states ───────────────────────────────────────────────────

    fun testMultipleDataStatesChainedViaDataTransitionOn() {
        // TypesafeTransitionTest "multiple data states" pattern: three states
        // connected by two dataTransitionOn calls. The chain state1→state2→state3
        // renders as two plain arrows; data types (String, Int) are not visible.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val state3 = dataState<Int>("state3")
                    val state2 = dataState<String>("state2") {
                        dataTransitionOn<IdEvent, Int> { targetState = { state3 } }
                    }
                    initialState("state1") {
                        dataTransitionOn<NameEvent, String> { targetState = { state2 } }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "state3" as state3
                  state "state2" as state2
                  state "state1" as state1
                  [*] --> state1
                }
                state2 --> state3 : IdEvent
                state1 --> state2 : NameEvent
                """
            ),
        )
    }

    // ── Mixed data and regular states ─────────────────────────────────────────

    fun testDataStatesCoexistWithRegularStatesInSameMachine() {
        // Data and plain states are siblings in the same machine. Only kind-based
        // arrows (initial/final) distinguish them visually. Transitions use both
        // dataTransition and plain transition callees in the same machine.
        assertPlantUml(
            source = """
                val machine = createStateMachine("m") {
                    val loggedIn = dataState<User>("loggedIn") {
                        transition<LogoutEvent> { targetState = loggedOut }
                    }
                    val loggedOut = finalState("loggedOut")
                    initialState("loginForm") {
                        dataTransition<LoginEvent, User> { targetState = loggedIn }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "loggedIn" as loggedIn
                  state "loggedOut" as loggedOut
                  state "loginForm" as loginForm
                  [*] --> loginForm
                  loggedOut --> [*]
                }
                loggedIn --> loggedOut : LogoutEvent
                loginForm --> loggedIn : LoginEvent
                """
            ),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertPlantUml(source: String, expected: String) {
        val file = myFixture.configureByText("Test.kt", source.trimIndent()) as KtFile
        val machines = PsiElementsParser { }.parse(file)
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
