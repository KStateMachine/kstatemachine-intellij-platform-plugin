<div align="center">

# KStateMachine Visual

**The IntelliJ Platform plugin for the [KStateMachine](https://github.com/KStateMachine/kstatemachine) Kotlin library**
**Visualize and navigate state machines straight from your source — no runtime needed**

---

[![Build](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/workflows/Build/badge.svg)](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/actions)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2023.3%20%E2%80%93%202024.2-blue?logo=intellijidea)](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
[![Slack](https://img.shields.io/badge/slack-kstatemachine-purple?logo=slack)](https://kotlinlang.slack.com/archives/C07DVAEKLM8)

---

[💾 Install](#-install) &nbsp;|&nbsp;
[✨ Features](#-features) &nbsp;|&nbsp;
[🚀 How it works](#-how-it-works) &nbsp;|&nbsp;
[🗺️ Roadmap](#%EF%B8%8F-roadmap) &nbsp;|&nbsp;
[🏗️ Build](#%EF%B8%8F-build-from-source) &nbsp;|&nbsp;
[🤝 Contributing](#-contributing) &nbsp;|&nbsp;
[💬 Discussions](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/discussions)

</div>

---

## What is KStateMachine Visual?

<!-- Plugin description -->
**KStateMachine Visual** is the IDE plugin for the [KStateMachine](https://github.com/KStateMachine/kstatemachine) Kotlin library.

It statically analyzes your Kotlin source — including `if` / `when` branches that the runtime PlantUML export cannot
see — and displays every state machine declared in the currently open file as both:

- a **navigable tree** of states, substates and transitions, and
- a **rendered UML state diagram**.

Click any node in the tree to jump to its declaration in the editor. Move the caret in the editor and the matching tree
node highlights automatically. Gutter icons next to `createStateMachine`, `state` and `transition` calls give one-click
access to the same view. The diagram updates live as you type and can be copied as PlantUML source or exported as PNG /
SVG.
<!-- Plugin description end -->

> [!NOTE]
> The plugin is in early development. Feedback on real-world DSL usage is especially welcome — open an issue or drop
> into the [Slack channel](https://kotlinlang.slack.com/archives/C07DVAEKLM8).

---

## ✨ Features

| Capability                                                                     | KStateMachine Visual |
|--------------------------------------------------------------------------------|:--------------------:|
| Static parsing of nested DSL — arbitrary depth                                 |          ✅           |
| Detects states declared inside `if` / `when` branches                          |          ✅           |
| Tree view with icons per state kind (initial, final, choice, history, data, …) |          ✅           |
| Bidirectional editor ↔ tree navigation                                         |          ✅           |
| Rendered PlantUML diagram in-panel (Smetana layout — no Graphviz needed)       |          ✅           |
| PlantUML source view alongside the diagram                                     |          ✅           |
| Transition arrows resolved from `targetState = …`                              |          ✅           |
| Editor gutter icons next to KStateMachine DSL calls                            |          ✅           |
| Live, debounced refresh while typing                                           |          ✅           |
| Copy PlantUML source / Export diagram as PNG or SVG                            |          ✅           |

---

## 🚀 How it works

Open any Kotlin file containing a KStateMachine declaration. The **KStateMachine** tool window shows two tabs that stay
in sync with your cursor.

**Structure tab** — a tree of every machine in the file:

```
StateMachine traffic  (4 states, 3 transitions)
├─ red  (initial)  (1 transition)
│   ├─ blinking
│   └─ on SwitchEvent  → yellow
├─ yellow  (1 transition)
│   └─ on SwitchEvent  → green
└─ green  (final)
```

**Diagram tab** — a rendered PlantUML state chart with the generated PlantUML source shown beneath it in a resizable
split.

The parser walks the PSI tree of `state { … }` and `transition { … }` lambdas, attributing every nested call to its
lexical parent — which is why `if` / `when` branches that the runtime PlantUML export can't see are still captured here.

---

## 💾 Install

### From JetBrains Marketplace _(once published)_

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search **"KStateMachine Visual"
** → <kbd>Install</kbd>

Or visit the [Marketplace page](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and click <kbd>Install to …</kbd>.

### Manual install from a downloaded archive

1. Grab the latest `.zip` from
   the [GitHub Releases](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/releases/latest) page,
   or from the [Marketplace versions page](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions).
2. In your IDE: <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from
   disk…</kbd>

**Compatibility:** IntelliJ Platform 2023.3 – 2024.2 (Community or Ultimate), plus other JetBrains IDEs in the same
range. Requires the bundled Kotlin and Java plugins.

---

## 🗺️ Roadmap

**Shipped**

- Recursive parsing of nested DSL — including states inside `if` / `when` branches
- Tree view of state machines in the current file, with icons per state kind
- Bidirectional editor ↔ tree navigation — click a node to jump; move the caret to highlight
- PlantUML state diagram rendered in-panel (Smetana layout, no Graphviz required)
- Transition arrows resolved from `targetState = …`
- Editor gutter icons next to KStateMachine DSL calls
- Live, debounced refresh while typing
- Copy PlantUML source / Export diagram as PNG or SVG

**Planned**

- [ ] Project-wide tab listing every machine across the project
- [ ] Rename refactoring of state names via the tree
- [ ] "Find Usages" for states (where is `targetState = redState` referenced?)
- [ ] Inspection: detect unreachable states and missing initial states
- [ ] Open generated PlantUML in browser (kroki.io / plantuml.com)
- [ ] Live preview balloon inside the editor, anchored to the machine

---

## 🏗️ Build from source

```bash
./gradlew buildPlugin           # produces a marketplace-ready ZIP in build/distributions/
./gradlew runIde                # launch a sandbox IDE with the plugin loaded
./gradlew verifyPlugin          # check the plugin against IntelliJ guidelines
```

Or open the project in IntelliJ IDEA and run the same tasks from the Gradle tool window.

---

## 🤝 Contributing

Bug reports, feature requests, and pull requests are welcome.
The plugin is in early development — issues that include a minimal reproducible KStateMachine DSL snippet are the most
actionable.

---

## 🙋 Support

| Channel                                                                                                   | Best for                           |
|-----------------------------------------------------------------------------------------------------------|------------------------------------|
| [Slack `#kstatemachine`](https://kotlinlang.slack.com/archives/C07DVAEKLM8)                               | Quick questions, discussion        |
| [GitHub Issues](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/issues)           | Bug reports & feature requests     |
| [GitHub Discussions](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/discussions) | Longer-form questions              |
| [KStateMachine library repo](https://github.com/KStateMachine/kstatemachine)                              | Questions about the library itself |

---

<details>
<summary><b>Maintainer checklist (template setup todos)</b></summary>

- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [x] Get familiar with the [template documentation][template].
- [x] Adjust the [pluginGroup](./gradle.properties) and [pluginName](./gradle.properties), as well as
  the [id](./src/main/resources/META-INF/plugin.xml) and [sources package](./src/main/kotlin).
- [x] Adjust the plugin description in `README` (see [Tips][docs:plugin-description])
- [ ] Review
  the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate)
  for the first time.
- [ ] Set the `MARKETPLACE_ID` in the badges above. You can obtain it once the plugin is published to JetBrains
  Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate)
  related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set
  the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [x] Click <kbd>Watch</kbd> on the [IntelliJ Platform Plugin Template][template] repo to get release notifications.

</details>

---

<div align="center">

Built on the [IntelliJ Platform Plugin Template][template].

</div>

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
