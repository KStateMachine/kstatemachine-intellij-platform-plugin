package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Round-trip tests for the parser + diagram generator pipeline. Each test
 * feeds a snippet of KStateMachine-like DSL Kotlin source, runs the full
 * pipeline (PSI parse → State/Transition model → [PlantUmlGenerator.render]),
 * and compares the rendered PlantUML against an expected diagram literal.
 *
 * Why this style:
 *   - The parser walks real Kotlin PSI, so the test needs an IntelliJ project
 *     fixture. [BasePlatformTestCase] gives us `myFixture` with a light
 *     project and Kotlin file-type support (the `org.jetbrains.kotlin`
 *     bundled plugin is loaded in test mode — see `platformBundledPlugins`
 *     in `gradle.properties`).
 *   - The test method names are JUnit3-style (`testXxx`) because the class
 *     hierarchy is JUnit3-based — that's a hard constraint of the IntelliJ
 *     test framework. Kotest's runner can't be plugged in there.
 *   - Kotest assertions ([shouldBe]) ARE plain functions and work just fine
 *     inside JUnit3 methods. They give nicer multi-line diff output than
 *     `assertEquals` when a diagram drifts.
 *
 * The parser is PSI-only — no binding context or imports required. Test
 * sources can omit `import` lines: as long as the callee text matches the
 * KStateMachine factory catalog (`createStateMachine`, `initialState`,
 * `state`, `transition`, etc.) the parser recognises them.
 */
class PlantUmlGeneratorTest : BasePlatformTestCase() {

    fun testSingleInitialStateRendersBareDiagram() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    initialState("Red")
                }
            """,
            expected = """
                @startuml
                top to bottom direction
                hide empty description

                state "machine" as machine {
                  state "Red" as Red
                  [*] --> Red
                }
                @enduml
            """,
        )
    }

    fun testReceiverScopedTransitionLandsOnSourceState() {
        // Receiver-scoped factory call (`red.transition<…>(…)`) — the
        // transition should land on Red's scope, not on the surrounding
        // machine. Also exercises target resolution through a variable
        // initializer chain (`val green = state("Green")`).
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val red = initialState("Red")
                    val green = state("Green")
                    red.transition<TickEvent>(targetState = green)
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "Red" as Red
                  state "Green" as Green
                  [*] --> Red
                }
                Red --> Green : TickEvent
            """
            ),
        )
    }

    fun testUmlMetaInfoLabelDescriptionsAndNotes() {
        // Mirrors PlantUmlExportWithUmlMetaInfoSample:
        //   machine-level umlLabel → state "Nested states sm" as machine
        //   state umlLabel        → state "FinalState 2 Label" as State2
        //   umlStateDescriptions  → State2 : Description N  (inside State2's block — but State2 is a leaf here)
        //   umlNotes on state     → note right of State2 : Note N
        //   transition umlLabel   → used as the arrow label
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine(name = "MySm") {
                    metaInfo = buildUmlMetaInfo { umlLabel = "Nested states sm" }
                    val state2 = finalState("State2") {
                        metaInfo = buildUmlMetaInfo {
                            umlLabel = "FinalState 2 Label"
                            umlStateDescriptions = listOf("Description 1", "Description 2")
                            umlNotes = listOf("Note 1", "Note 2")
                        }
                    }
                    initialState("State1") {
                        metaInfo = buildUmlMetaInfo { umlLabel = "State 1 Label" }
                        transition<SwitchEvent> {
                            metaInfo = buildUmlMetaInfo { umlLabel = "go to State 2" }
                            targetState = state2
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "Nested states sm" as MySm {
                  state "FinalState 2 Label" as State2
                  State2 : Description 1
                  State2 : Description 2
                  note right of State2 : Note 1
                  note right of State2 : Note 2
                  state "State 1 Label" as State1
                  [*] --> State1
                  State2 --> [*]
                }
                State1 --> State2 : go to State 2 <SwitchEvent>
                """
            ),
        )
    }

    fun testUmlMetaInfoTransitionNote() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val state2 = finalState("State2")
                    initialState("State1") {
                        transition<SwitchEvent> {
                            metaInfo = buildUmlMetaInfo { umlNotes = listOf("Note 1", "Note 2") }
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
                  State2 --> [*]
                }
                State1 --> State2 : SwitchEvent
                note on link
                  Note 1
                end note
                note on link
                  Note 2
                end note
                """
            ),
        )
    }

    fun testUmlMetaInfoCompositeMetaInfo() {
        // buildCompositeMetaInfo vararg form — same rendering as direct buildUmlMetaInfo
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    metaInfo = buildCompositeMetaInfo(buildUmlMetaInfo { umlLabel = "My Machine" })
                    initialState("State1")
                }
            """,
            expected = bodyTags(
                """
                state "My Machine" as machine {
                  state "State1" as State1
                  [*] --> State1
                }
                """
            ),
        )
    }

    fun testShallowHistoryStateUsesPlantUmlPseudoStateNotation() {
        // history states must NOT appear as state declarations; transitions to
        // them use `parentId[H]` (shallow) or `parentId[H*]` (deep) notation.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    initialState("State2")
                    state("State3") {
                        val h = historyState("H")
                        initialState("s31")
                        transition<PauseEvent> { targetState = h }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State3" as State3 {
                    state "s31" as s31
                    [*] --> s31
                  }
                  [*] --> State2
                }
                State3 --> State3[H] : PauseEvent
                """
            ),
        )
    }

    fun testDeepHistoryStateUsesPlantUmlPseudoStateNotation() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    initialState("State2")
                    state("State3") {
                        val h = historyState("HD", historyType = HistoryType.DEEP)
                        initialState("s31")
                        transition<PauseEvent> { targetState = h }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "State2" as State2
                  state "State3" as State3 {
                    state "s31" as s31
                    [*] --> s31
                  }
                  [*] --> State2
                }
                State3 --> State3[H*] : PauseEvent
                """
            ),
        )
    }

    fun testJoinDataTransitionRendersJoinPseudoState() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val afterJoin = dataState<String>("afterJoin")
                    initialState("parallel", childMode = ChildMode.PARALLEL) {
                        state("region1") {
                            val jp1 = state("jp1")
                            initialState("s1") {
                                transition<SwitchEvent> { targetState = jp1 }
                            }
                        }
                        state("region2") {
                            val jp2 = state("jp2")
                            initialState("s2") {
                                transition<SwitchEventL1> { targetState = jp2 }
                            }
                        }
                        joinDataTransition(jp1, jp2, targetState = afterJoin) { "joined" }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "afterJoin" as afterJoin
                  state "parallel" as parallel {
                    state "region1" as region1 {
                      state "jp1" as jp1
                      state "s1" as s1
                      s1 --> jp1 : SwitchEvent
                      [*] --> s1
                    }
                    [*] --> region1
                    --
                    state "region2" as region2 {
                      state "jp2" as jp2
                      state "s2" as s2
                      s2 --> jp2 : SwitchEventL1
                      [*] --> s2
                    }
                    [*] --> region2
                    state join_parallel_0 <<join>>
                  }
                  [*] --> parallel
                }
                jp1 --> join_parallel_0
                jp2 --> join_parallel_0
                join_parallel_0 --> afterJoin
                """
            ),
        )
    }

    fun testJoinTransitionRendersJoinPseudoState() {
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine {
                    val processing = state("processing")
                    initialState("parallelWork", childMode = ChildMode.PARALLEL) {
                        state("download") {
                            val downloadJoin = state("downloadJoin")
                            initialState("downloading") {
                                transition<DownloadCompleteEvent> { targetState = downloadJoin }
                            }
                        }
                        state("validate") {
                            val validationJoin = state("validationJoin")
                            initialState("validating") {
                                transition<ValidationCompleteEvent> { targetState = validationJoin }
                            }
                        }
                        joinTransition(downloadJoin, validationJoin, targetState = processing)
                    }
                }
            """,
            expected = bodyTags(
                """
                state "machine" as machine {
                  state "processing" as processing
                  state "parallelWork" as parallelWork {
                    state "download" as download {
                      state "downloadJoin" as downloadJoin
                      state "downloading" as downloading
                      downloading --> downloadJoin : DownloadCompleteEvent
                      [*] --> downloading
                    }
                    [*] --> download
                    --
                    state "validate" as validate {
                      state "validationJoin" as validationJoin
                      state "validating" as validating
                      validating --> validationJoin : ValidationCompleteEvent
                      [*] --> validating
                    }
                    [*] --> validate
                    state join_parallelWork_0 <<join>>
                  }
                  [*] --> parallelWork
                }
                downloadJoin --> join_parallelWork_0
                validationJoin --> join_parallelWork_0
                join_parallelWork_0 --> processing
                """
            ),
        )
    }
}