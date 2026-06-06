package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtFile

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
        assertPlantUml(
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
        assertPlantUml(
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

    fun testJoinDataTransitionRendersJoinPseudoState() {
        assertPlantUml(
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
        assertPlantUml(
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

    /**
     * Pipeline harness: load [source] as a Kotlin file via the light fixture,
     * run the parser, render the (single expected) machine via the generator,
     * and compare against [expected]. Both arguments are passed through
     * `trimIndent` so callers can use multi-line string literals naturally.
     */
    private fun assertPlantUml(source: String, expected: String) {
        val file = myFixture.configureByText("Test.kt", source.trimIndent()) as KtFile
        val machines = PsiElementsParser { /* discard log output */ }.parse(file)
        require(machines.size == 1) {
            "Expected exactly one state machine in source, got ${machines.size}"
        }
        val rendered = PlantUmlGenerator.render(machines.single()).trim()
        rendered shouldBe expected.trimIndent().trim()
    }
}

fun bodyTags(expected: String) = """
    @startuml
    top to bottom direction
    hide empty description

${expected.trimIndent().trim().prependIndent("    ")}
    @enduml
""".trimIndent()