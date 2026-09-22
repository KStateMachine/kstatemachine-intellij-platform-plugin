# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Plugin Does

An IntelliJ Platform plugin for [KStateMachine](https://github.com/KStateMachine/kstatemachine). It statically analyzes Kotlin source files to extract state machine definitions, states, and transitions defined with the KStateMachine DSL, then displays findings in a tool window. Currently in early development — the parser is functional but visualization is still log-based.

## Build & Test Commands

```bash
# Build the plugin ZIP
./gradlew buildPlugin

# Run tests
./gradlew test

# Run in a sandboxed IDE instance
./gradlew runIde

# Verify plugin structure against IntelliJ guidelines
./gradlew verifyPlugin

# Code coverage report (Kover)
./gradlew koverReport

# Code quality inspection (Qodana)
./gradlew qodanaAnalyze
```

Run a single test class:
```bash
./gradlew test --tests "com.github.nsk90.kstatemachineintellijplatformplugin.MyPluginTest"
```

## Architecture

The plugin has four layers:

**Model** (`model/`) — Plain data classes: `StateMachine` (extends `State`), `State`, `Transition`. These are the output of parsing.

**Parser** (`psi/PsiElementsParser.kt`) — The core logic. Traverses Kotlin PSI trees using IntelliJ's binding context to resolve fully-qualified function names, then matches against KStateMachine's creation functions (`createStateMachine`, `createStateMachineBlocking`, etc.), state factory functions (`state()`, `initialState()`, `dataState()`, etc.), and transition functions (`transition()`, `transitionOn()`, etc.). All recognized function names are defined as constants at the top of the file grouped by `ru.nsk.kstatemachine.*` package paths. The import-aware detection (added recently) is what makes matching reliable.

**Service** (`services/FileSwitchService.kt`) — A project-level service that wraps a `MutableSharedFlow` and emits events when the user switches editor tabs.

**Tool Window** (`toolWindow/MainToolWindowFactory.kt`) — Subscribes to `FileSwitchService`, runs the parser in a background `ProgressTask`, and writes results to a log text area. Uses a coroutine scope that is cancelled on disposal.

## IntelliJ Platform Details

- Target platform: compiled against IntelliJ Ultimate (IU) 2026.2.1; compatible range 262–263.*
  (i.e. 2026.2 through 2026.3). One build serves both branches — the parser and the JCEF renderer
  use only APIs present in each, confirmed by `verifyPlugin`.
  (2026.1 dropped in 0.3.0: its older JCEF lacks the `CefResourceHandler.open/read/skip` API that
  2026.2 requires — see `PlantUmlJsRenderer.BundledResourceHandler`)
- Do not bump `platformVersion` to 2026.2.3: that IU build crashes `BasePlatformTestCase` project
  startup inside an obfuscated `com.intellij.modules.ultimate` post-startup activity
  ("Cannot find suitable constructor for class Z.Z.Z.Z.Z"), which fails all 115 tests. Not our
  bug, and not a runtime problem — only the test harness. Recheck on the next patch release.
- While 2026.3 is EAP-only, `pluginVerification.ides.recommended()` resolves released IDEs only and
  would skip the 263 branch entirely, so `build.gradle.kts` adds a `select {}` for the latest 263
  EAP. Remove that block once 2026.3 ships.
- Platform version and build range live in `gradle.properties` (`platformVersion`, `pluginSinceBuild`, `pluginUntilBuild`).
  When bumping them, also update the compatibility badge and "Compatibility" line in README.md.
- Declared plugin dependencies: `com.intellij.modules.platform`, `com.intellij.java`, `org.jetbrains.kotlin`
- Plugin descriptor: `src/main/resources/META-INF/plugin.xml`
- Localized strings: `src/main/resources/messages/MyBundle.properties` + `MyBundle.kt` wrapper

When adding new extension points (tool windows, actions, services), register them in `plugin.xml`.

## Key Conventions

- When adding new KStateMachine function names to detect, add them to the constant sets at the top of `PsiElementsParser.kt` (not inline in the parsing logic).
- PSI traversal uses `KtCallExpression` and binding context for FQN resolution — prefer this pattern over string-matching import statements.
- Background work in the tool window uses `object : Task.Backgroundable(project, ...) { override fun run(...) }` with `ApplicationManager.invokeLater` for any UI updates.
