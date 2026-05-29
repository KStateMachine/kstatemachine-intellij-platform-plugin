# KStateMachine Visual

![Build](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [x] Get familiar with the [template documentation][template].
- [x] Adjust the [pluginGroup](./gradle.properties) and [pluginName](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml) and [sources package](./src/main/kotlin).
- [x] Adjust the plugin description in `README` (see [Tips][docs:plugin-description])
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [x] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

<!-- Plugin description -->
**KStateMachine Visual** is the official IDE plugin for the [KStateMachine](https://github.com/KStateMachine/kstatemachine) Kotlin library.

It statically analyzes your Kotlin source — including `if` / `when` branches that the runtime PlantUML export cannot see — and displays every state machine declared in the currently open file as both:

- a **navigable tree** of states, substates and transitions, and
- a **rendered UML state diagram**.

Click any node in the tree to jump to its declaration in the editor. Move the caret in the editor and the matching tree node highlights automatically. Gutter icons next to `createStateMachine`, `state` and `transition` calls give one-click access to the same view. The diagram updates live as you type and can be copied as PlantUML source or exported as PNG / SVG.
<!-- Plugin description end -->

## Roadmap

**Shipped**
- Recursive parsing of nested DSL (including states inside `if` / `when` branches)
- Tree view of state machines in the current file, with icons per state kind (initial / final / choice / history / data / …)
- Bidirectional editor ↔ tree navigation (click a node to jump; move the caret to highlight)
- PlantUML state diagram rendered in-panel (Smetana layout, no Graphviz required)
- Transition arrows in the diagram resolved from `targetState = …`
- Editor gutter icons next to KStateMachine DSL calls
- Live, debounced refresh while typing
- Copy PlantUML source / Export diagram as PNG or SVG

**Planned**
- Project-wide tab listing every machine across the project
- Rename refactoring of state names via the tree
- "Find Usages" for states (where is `targetState = redState` referenced?)
- Inspection: detect unreachable states and missing initial states
- Open generated PlantUML in browser (kroki.io / plantuml.com)
- Live preview balloon inside the editor, anchored to the machine

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "KStateMachine Visual"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
