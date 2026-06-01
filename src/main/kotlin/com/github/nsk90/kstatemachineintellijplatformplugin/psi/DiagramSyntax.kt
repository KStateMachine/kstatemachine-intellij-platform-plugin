package com.github.nsk90.kstatemachineintellijplatformplugin.psi

/**
 * Which diagram-as-code syntax [PlantUmlGenerator.render] emits and which
 * renderer the Diagram tab uses. PlantUML and Mermaid share most state-diagram
 * syntax — only header, footer, theme directive, and a couple of state-decl
 * details differ — so a single generator produces either form via this flag.
 */
enum class DiagramSyntax(val displayName: String) {
    PLANTUML("PlantUML"),
    MERMAID("Mermaid"),
    ;

    override fun toString(): String = displayName
}
