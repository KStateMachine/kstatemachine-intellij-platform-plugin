<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# KStateMachine Visual Changelog

## [Unreleased]

## [0.2.0] - 2026-06-16

### Added

- Zoom support for the diagram view — scroll or pinch to zoom, diagram stays within the window bounds.
- Support for `autoTransition`, `autoTransitionOn`, `autoTransitionConditionally`, `autoDataTransition`,
  and `autoDataTransitionOn` (UML eventless / "always" transitions).
- Support for extended join-transition family: `joinTransitionOn`, `joinTransitionConditionally`,
  `joinDataTransitionOn`; join-source extraction for Set and builder-style calling forms.

### Fixed

- Fixed initial diagram scaling when the tool window first opens.
- Fixed diagram area not resizing correctly when the IDE window is resized.
- Fixed a race condition where editing the current file and then switching tabs left the plugin showing
  stale machines, breaking caret-based tree navigation.
- Fixed plugin getting stuck after a file is renamed.
- Reduced flickering when switching between PlantUML and Mermaid diagram formats.
- Fixed Mermaid visual rendering artifacts.
- Fixed minor spacing layout issues in the diagram panel.

## [0.1.0] - 2026-06-09

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

[Unreleased]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/compare/0.2.0...HEAD
[0.2.0]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/commits/0.1.0
