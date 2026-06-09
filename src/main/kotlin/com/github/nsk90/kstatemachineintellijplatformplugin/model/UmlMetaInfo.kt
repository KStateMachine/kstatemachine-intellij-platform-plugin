package com.github.nsk90.kstatemachineintellijplatformplugin.model

/** Parsed [buildUmlMetaInfo] annotation attached to a state or transition. */
data class UmlMetaInfo(
    /** Overrides the display name: emits `state "label" as id` in PlantUML. */
    val label: String? = null,
    /** State-only: emits `id : line` description rows at the top of the state body. */
    val stateDescriptions: List<String> = emptyList(),
    /**
     * State: emits `note right of id : …` at the top of the state body.
     * Transition: emits `note on link / … / end note` after the arrow (PlantUML only).
     */
    val notes: List<String> = emptyList(),
)
