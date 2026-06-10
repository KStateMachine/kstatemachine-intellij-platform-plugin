<div align="center">

# KStateMachine Visual

**IntelliJ plugin for [KStateMachine](https://github.com/KStateMachine/kstatemachine) — visualize and navigate state machines straight from your source.**

---

[![Build](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/workflows/Build/badge.svg)](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/actions)
[![Version](https://img.shields.io/jetbrains/plugin/v/32202-kstatemachine-visual.svg)](https://plugins.jetbrains.com/plugin/32202-kstatemachine-visual)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32202-kstatemachine-visual.svg)](https://plugins.jetbrains.com/plugin/32202-kstatemachine-visual)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2026.1%20%E2%80%93%202026.1*-blue?logo=intellijidea)](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
[![Slack](https://img.shields.io/badge/slack-kstatemachine-purple?logo=slack)](https://kotlinlang.slack.com/archives/C07DVAEKLM8)

[💾 Install](#-install) &nbsp;·&nbsp;
[✨ Features](#-features) &nbsp;·&nbsp;
[🗺️ Roadmap](#%EF%B8%8F-roadmap) &nbsp;·&nbsp;
[🏗️ Build](#%EF%B8%8F-build) &nbsp;·&nbsp;
[💬 Discussions](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/discussions)

</div>

---

<!-- Plugin description -->
**KStateMachine Visual** is the IDE plugin for the [KStateMachine](https://github.com/KStateMachine/kstatemachine) Kotlin library.

It statically analyzes your Kotlin source — including `if` / `when` branches that the runtime PlantUML export cannot see — and shows every state machine in the current file as both a navigable tree and a rendered UML state diagram.

Click any tree node to jump to its declaration; move the editor caret and the tree selection follows. Gutter icons mark every `createStateMachine` / `state` / `transition` call. The diagram updates live as you type, and the PlantUML source can be copied or exported as SVG.
<!-- Plugin description end -->

> [!NOTE]
> Early development — feedback on real-world DSL usage especially welcome.

---

## ✨ Features

- Recursive parsing of nested DSL — any depth, including states inside `if` / `when` branches
- Tree view with per-kind icons (initial, final, choice, history, data, mutable data, parallel, …)
- Bidirectional editor ↔ tree navigation
- Rendered PlantUML state diagram in-panel (Smetana layout — no Graphviz needed)
- Editable Playground tab for ad-hoc PlantUML (e.g. runtime exports)
- Editor gutter icons next to every KStateMachine DSL call
- Live, debounced refresh while typing
- Copy PlantUML source / Export diagram as PNG or SVG

---

## 💾 Install

**From Marketplace** — <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search **"KStateMachine Visual"**, or open the [plugin page](https://plugins.jetbrains.com/plugin/32202-kstatemachine-visual) and click **Install**.

**Manual** — download the `.zip` from [Releases](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/releases/latest) and use <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>.

**Compatibility:** IntelliJ Platform 2026.1+ (Community or Ultimate), bundled Kotlin + Java plugins required.

---

## 🗺️ Roadmap

**Planned**

- [ ] Project-wide tab listing every machine across the project
- [ ] Rename refactoring of state names via the tree
- [ ] Inspection: unreachable states and missing initial states
- [ ] Open generated PlantUML in browser (kroki.io / plantuml.com)
- [ ] In-editor preview balloon anchored to the machine

---

## 🏗️ Build

```bash
./gradlew buildPlugin     # marketplace-ready ZIP in build/distributions/
./gradlew runIde          # launch sandbox IDE with the plugin loaded
./gradlew verifyPlugin    # check against IntelliJ guidelines
```

---

## 🤝 Contributing & support

PRs and issues welcome. Reports with a minimal reproducible DSL snippet are the most actionable.

| Channel | Best for |
|---|---|
| [Slack `#kstatemachine`](https://kotlinlang.slack.com/archives/C07DVAEKLM8) | Quick questions |
| [GitHub Issues](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/issues) | Bug reports, features |
| [GitHub Discussions](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/discussions) | Longer-form questions |
| [KStateMachine library](https://github.com/KStateMachine/kstatemachine) | Questions about the library |

---

<div align="center">

Licensed under the [MIT License](./LICENSE)

</div>

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
