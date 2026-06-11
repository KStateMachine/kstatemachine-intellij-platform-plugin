package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase


/**
 * Tests covering `metaInfo = buildUmlMetaInfo { … }` and `buildCompositeMetaInfo` parsing
 * and PlantUML / Mermaid diagram rendering in KStateMachine.
 *
 * **What the parser does:**
 *   - `metaInfo = buildUmlMetaInfo { … }` inside a state's DSL lambda is located via
 *     `findLambdaAssignmentEntry("metaInfo")` and then `parseUmlMetaInfoLambda()` extracts
 *     `umlLabel`, `umlStateDescriptions`, and `umlNotes` from the body.
 *   - `buildCompositeMetaInfo(buildUmlMetaInfo { … }, …)` (vararg form) — all arguments are
 *     walked via `parseUmlMetaInfoFromExpr`; the first that is itself a `buildUmlMetaInfo`
 *     call is returned (so UmlMetaInfo need not be the first positional argument).
 *   - `buildCompositeMetaInfo { metaInfoSet = setOf(buildUmlMetaInfo { … }) }` (builder form)
 *     — the parser recurses through the `setOf(…)` call to find the nested
 *     `buildUmlMetaInfo { … }` call.
 *   - An empty `buildUmlMetaInfo {}` (no fields assigned) produces null — `parseUmlMetaInfoLambda`
 *     returns null when all three fields are at their defaults.
 *   - `metaInfo = …` written inside an extension-invoke lambda (`state1 { metaInfo = … }`)
 *     is NOT captured by the current parser: `extractUmlMetaInfo()` is called on the original
 *     factory call's own trailing lambda, not on the extension-invoke extras.
 *
 * **What the generator does:**
 *   - PlantUML: `umlLabel` overrides the state's display name in `state "label" as id`;
 *     `umlStateDescriptions` emits `id : text` lines; `umlNotes` emits `note right of id : text`.
 *     On a non-leaf state the annotations are emitted INSIDE the block before the children.
 *   - Mermaid: only `umlLabel` is applied; descriptions and notes are fully suppressed
 *     (Mermaid stateDiagram-v2 has no equivalent syntax).
 *   - On a transition: `umlLabel` overrides the arrow label (event type appended in `<…>`
 *     unless the label text already contains it); `umlNotes` emits `note on link / … / end note`
 *     blocks after the arrow (PlantUML only).
 *
 * Source samples are drawn from PlantUmlExportWithUmlMetaInfoSample and UmlMetaInfoTest in
 * the KStateMachine library.
 */
class MetaInfoTest : BasePlatformTestCase() {

    // ── buildUmlMetaInfo on states — individual fields ─────────────────────────

    fun testStateUmlLabelOnlyOverridesDisplayName() {
        // umlLabel replaces the state's display name in the diagram but leaves
        // the sanitized id (derived from the DSL name "S1") unchanged.
        // No descriptions or notes → no extra lines.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo { umlLabel = "My First State" }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "My First State" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }

    fun testStateUmlStateDescriptionsOnlyAddsDescriptionRows() {
        // umlStateDescriptions emits `id : text` for each entry at the same
        // indent as the state declaration (leaf case). No label → display name
        // stays as the DSL name. No notes → no `note right of …` lines.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo {
                            umlStateDescriptions = listOf("Entry: start timer", "Do: process")
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S1" as S1
                  S1 : Entry: start timer
                  S1 : Do: process
                  [*] --> S1
                }
                """
            ),
        )
    }

    fun testStateUmlNotesOnlyAddsNoteRightOfLines() {
        // umlNotes emits `note right of id : text` for each entry.
        // No label and no descriptions.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo {
                            umlNotes = listOf("This is a note", "Second note")
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S1" as S1
                  note right of S1 : This is a note
                  note right of S1 : Second note
                  [*] --> S1
                }
                """
            ),
        )
    }

    fun testStateAllMetaInfoFieldsTogetherOnLeafState() {
        // All three fields active simultaneously on a leaf state: label overrides
        // the display name; descriptions (id : …) and notes (note right of …) are
        // both appended at the same indent as the state declaration.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo {
                            umlLabel = "First State"
                            umlStateDescriptions = listOf("desc line")
                            umlNotes = listOf("a note")
                        }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "First State" as S1
                  S1 : desc line
                  note right of S1 : a note
                  [*] --> S1
                }
                """
            ),
        )
    }

    // ── buildUmlMetaInfo on composite (non-leaf) states ────────────────────────

    fun testMetaInfoOnCompositeStateEmitsAnnotationsInsideBlock() {
        // On a non-leaf state the generator emits descriptions and notes INSIDE
        // the `state { … }` block, before the children — mirroring
        // ExportPlantUmlVisitor.processStateBody. The id in the description rows
        // is the sanitized id ("Work"), not the label ("Working State").
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Work") {
                        metaInfo = buildUmlMetaInfo {
                            umlLabel = "Working State"
                            umlStateDescriptions = listOf("Processing data")
                            umlNotes = listOf("Important")
                        }
                        initialState("Running")
                        state("Paused")
                    }
                    initialState("Idle")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Working State" as Work {
                    Work : Processing data
                    note right of Work : Important
                    state "Running" as Running
                    state "Paused" as Paused
                    [*] --> Running
                  }
                  state "Idle" as Idle
                  [*] --> Idle
                }
                """
            ),
        )
    }

    fun testMetaInfoOnNestedStateIsIndependentFromParent() {
        // Each state carries its own UmlMetaInfo; parent and child labels are
        // applied independently without bleeding into each other.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    state("Parent") {
                        metaInfo = buildUmlMetaInfo { umlLabel = "Parent Label" }
                        initialState("Child") {
                            metaInfo = buildUmlMetaInfo { umlLabel = "Child Label" }
                        }
                    }
                    initialState("Start")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "Parent Label" as Parent {
                    state "Child Label" as Child
                    [*] --> Child
                  }
                  state "Start" as Start
                  [*] --> Start
                }
                """
            ),
        )
    }

    // ── buildUmlMetaInfo on transitions ────────────────────────────────────────

    fun testTransitionUmlLabelAndNotesTogether() {
        // Both umlLabel (overrides arrow label) and umlNotes (emits note-on-link
        // blocks) are active on the same transition. The event type is appended in
        // `<…>` because the label text "Go to final" does not contain "Submit" as
        // a substring. The `contains` guard in transitionLabel prevents duplication
        // when the label already names the event (see testTransitionUmlLabelMatchingEventTypeIsNotDuplicated).
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val s2 = finalState("S2")
                    initialState("S1") {
                        transition<Submit> {
                            metaInfo = buildUmlMetaInfo {
                                umlLabel = "Go to final"
                                umlNotes = listOf("Validates first")
                            }
                            targetState = s2
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
                  S2 --> [*]
                }
                S1 --> S2 : Go to final <Submit>
                note on link
                  Validates first
                end note
                """
            ),
        )
    }

    fun testTransitionUmlLabelMatchingEventTypeIsNotDuplicated() {
        // When umlLabel equals the event type exactly, `explicit.contains(event)`
        // is true so the generator does NOT append `<EventType>` — the label is
        // emitted as-is. This mirrors ExportPlantUmlVisitor's deduplication guard.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val s2 = state("S2")
                    initialState("S1") {
                        transition<SwitchEvent> {
                            metaInfo = buildUmlMetaInfo { umlLabel = "SwitchEvent" }
                            targetState = s2
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
                S1 --> S2 : SwitchEvent
                """
            ),
        )
    }

    // ── buildCompositeMetaInfo forms ───────────────────────────────────────────

    fun testCompositeMetaInfoBuilderFormExtractsUmlLabel() {
        // `buildCompositeMetaInfo { metaInfoSet = setOf(buildUmlMetaInfo { … }) }` —
        // the builder form. The parser recurses through the `setOf(…)` call to
        // find the nested `buildUmlMetaInfo { … }` and extract its label.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    metaInfo = buildCompositeMetaInfo {
                        metaInfoSet = setOf(buildUmlMetaInfo { umlLabel = "My Machine" })
                    }
                    initialState("S1")
                }
            """,
            expected = bodyTags(
                """
                state "My Machine" as m {
                  state "S1" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }

    fun testCompositeMetaInfoVarargFormOnTransitionExtractsUmlLabel() {
        // buildCompositeMetaInfo vararg form on a transition — the parser maps
        // all arguments through parseUmlMetaInfoFromExpr and returns the first
        // non-null result. Empty buildUmlMetaInfo{} produces null (no fields set)
        // and is filtered out by mapNotNull; the populated first arg wins.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val s2 = state("S2")
                    initialState("S1") {
                        transition<SwitchEvent> {
                            metaInfo = buildCompositeMetaInfo(buildUmlMetaInfo { umlLabel = "Go!" }, buildUmlMetaInfo {})
                            targetState = s2
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
                S1 --> S2 : Go! <SwitchEvent>
                """
            ),
        )
    }

    fun testCompositeMetaInfoVarargFormWithUmlMetaInfoNotFirstArg() {
        // When the first arg to buildCompositeMetaInfo vararg form is an empty
        // buildUmlMetaInfo{} (producing null) the parser's mapNotNull keeps only
        // the second arg's result. This verifies that UmlMetaInfo need not be
        // the first positional argument to be detected.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    metaInfo = buildCompositeMetaInfo(buildUmlMetaInfo {}, buildUmlMetaInfo { umlLabel = "My Machine" })
                    initialState("S1")
                }
            """,
            expected = bodyTags(
                """
                state "My Machine" as m {
                  state "S1" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }

    // ── Mermaid rendering of metaInfo ──────────────────────────────────────────

    fun testUmlLabelAppliedInMermaidMode() {
        // The umlLabel override applies in Mermaid mode exactly as in PlantUML —
        // the label becomes the display name in `state "label" as id`.
        assertMermaid(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo { umlLabel = "My First State" }
                    }
                }
            """,
            expected = """
                stateDiagram-v2
                    direction TB

                state "m" as m {
                  state "My First State" as S1
                  [*] --> S1
                }
            """,
        )
    }

    fun testStateDescriptionsAndNotesNotRenderedInMermaidMode() {
        // Mermaid stateDiagram-v2 has no `state : description` or
        // `note right of …` syntax. The generator suppresses both even when the
        // model carries them — only the label field takes effect in Mermaid mode.
        assertMermaid(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo {
                            umlLabel = "First State"
                            umlStateDescriptions = listOf("Entry: start timer")
                            umlNotes = listOf("important note")
                        }
                    }
                }
            """,
            expected = """
                stateDiagram-v2
                    direction TB

                state "m" as m {
                  state "First State" as S1
                  [*] --> S1
                }
            """,
        )
    }

    fun testTransitionNotesNotRenderedInMermaidMode() {
        // `note on link` annotations are PlantUML-only — Mermaid stateDiagram-v2
        // has no equivalent construct. The generator skips them entirely in
        // Mermaid mode.
        assertMermaid(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val s2 = state("S2")
                    initialState("S1") {
                        transition<SwitchEvent> {
                            metaInfo = buildUmlMetaInfo { umlNotes = listOf("Note on transition") }
                            targetState = s2
                        }
                    }
                }
            """,
            expected = """
                stateDiagram-v2
                    direction TB

                state "m" as m {
                  state "S2" as S2
                  state "S1" as S1
                  [*] --> S1
                }
                S1 --> S2 : SwitchEvent
            """,
        )
    }

    // ── Parser corner cases ────────────────────────────────────────────────────

    fun testEmptyBuildUmlMetaInfoLambdaProducesNoDiagramEffect() {
        // `buildUmlMetaInfo {}` with no fields assigned: parseUmlMetaInfoLambda
        // returns null when label is null AND both lists are empty. The diagram
        // is identical to the same state without any metaInfo assignment.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo {}
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S1" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }

    fun testMultipleStatesHaveIndependentMetaInfo() {
        // Each state carries its own UmlMetaInfo instance. Labels, descriptions,
        // and notes from one sibling must not bleed into another sibling.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    initialState("S1") {
                        metaInfo = buildUmlMetaInfo {
                            umlLabel = "First"
                            umlNotes = listOf("Note for S1")
                        }
                    }
                    state("S2") {
                        metaInfo = buildUmlMetaInfo {
                            umlLabel = "Second"
                            umlStateDescriptions = listOf("Desc for S2")
                        }
                    }
                    finalState("S3")
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "First" as S1
                  note right of S1 : Note for S1
                  state "Second" as S2
                  S2 : Desc for S2
                  state "S3" as S3
                  [*] --> S1
                  S3 --> [*]
                }
                """
            ),
        )
    }

    fun testMetaInfoInExtensionInvokeIsNotCapturedByCurrentParser() {
        // `metaInfo = buildUmlMetaInfo { … }` written inside the `state1 { … }`
        // extension-invoke lambda is NOT captured by the current parser.
        // `extractUmlMetaInfo()` is called on the original factory call
        // (`initialState("S1")`, which has no trailing lambda with `metaInfo`),
        // not on the extension-invoke lambdas folded into extras.
        // This test freezes the current (limited) parser behaviour so any future
        // improvement would be visible as a deliberate change here.
        assertPlantUml(myFixture, 
            source = """
                val machine = createStateMachine("m") {
                    val s1 = initialState("S1")
                    s1 {
                        metaInfo = buildUmlMetaInfo { umlLabel = "S1 Label" }
                    }
                }
            """,
            expected = bodyTags(
                """
                state "m" as m {
                  state "S1" as S1
                  [*] --> S1
                }
                """
            ),
        )
    }
}