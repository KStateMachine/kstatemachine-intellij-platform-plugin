<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# KStateMachine Visual Changelog

## [Unreleased]

## [0.4.0] - 2026-09-22

### Added

- Support for IntelliJ Platform 2026.3 — the compatibility range now spans 2026.2–2026.3
  (builds 262–263.*). A single build serves both; 2026.2 users are unaffected.

## [0.3.0] - 2026-08-19

### Added

- "Open in Online Editor" action — opens the current diagram in plantuml.com or mermaid.live, depending on the
  selected diagram format.

### Changed

- Built against IntelliJ Platform 2026.2.1.

### Removed

- Support for IntelliJ Platform 2026.1 — its JCEF build lacks the resource-handler API that 2026.2
  requires. 2026.1 users stay on 0.2.0, which the Marketplace keeps serving them.

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

[Unreleased]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/KStateMachine/kstatemachine-intellij-platform-plugin/commits/v0.1.0
