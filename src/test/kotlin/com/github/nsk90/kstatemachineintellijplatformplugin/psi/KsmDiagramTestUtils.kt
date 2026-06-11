package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.psi.KtFile

fun parseMachines(fixture: CodeInsightTestFixture, source: String): List<StateMachine> {
    val file = fixture.configureByText("Test.kt", source.trimIndent()) as KtFile
    return PsiElementsParser { }.parse(file)
}

fun parseSingleMachine(fixture: CodeInsightTestFixture, source: String): StateMachine {
    val machines = parseMachines(fixture, source)
    require(machines.size == 1) {
        "Expected exactly one state machine in source, got ${machines.size}"
    }
    return machines.single()
}

fun assertPlantUml(fixture: CodeInsightTestFixture, source: String, expected: String) {
    val rendered = PlantUmlGenerator.render(parseSingleMachine(fixture, source)).trim()
    rendered shouldBe expected.trimIndent().trim()
}

fun assertMermaid(fixture: CodeInsightTestFixture, source: String, expected: String) {
    val rendered = PlantUmlGenerator.render(
        parseSingleMachine(fixture, source),
        syntax = DiagramSyntax.MERMAID,
    ).trim()
    rendered shouldBe expected.trimIndent().trim()
}

fun bodyTags(expected: String) = """
    @startuml
    top to bottom direction
    hide empty description

${expected.trimIndent().trim().prependIndent("    ")}
    @enduml
""".trimIndent()
