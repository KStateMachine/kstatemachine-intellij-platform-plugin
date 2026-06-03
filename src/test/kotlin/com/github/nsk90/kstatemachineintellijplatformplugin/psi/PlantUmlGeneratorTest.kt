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