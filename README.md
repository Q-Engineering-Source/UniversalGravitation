# Universal Gravitation

Universal Gravitation (UG) is a work-in-progress gravity and player-motion mod for Minecraft 1.12.2 on Cleanroom. Its pure-Java API models gravity fields and orbital mechanics; the Minecraft integration adds world fields, gravity and magnetic floors, and experimental player movement. This repository contains source code and tests, not a validated release or a ready-to-play modpack.

## Build prerequisites

- Java 25 and the Gradle wrapper in this repository.
- The exact CrlHitbox (CHB) development and runtime artifacts identified by `gradle/chb-artifact.properties`. CHB is a separate required mod; UG does not bundle it.
- NeoFontRender UI Enhancements (UIE) API classes from an exact local build or a digest-verified artifact. `uie_classes_dir` in `gradle.properties` is a local compile-time input, not a downloaded dependency. UIE is optional at runtime and is not bundled into UG.

With these prerequisites available, run `gradlew.bat build` on Windows or `./gradlew build` on Unix-like systems. The pinned external artifacts are not included in this repository, so a fresh clone alone is not sufficient to complete the build. A successful build does not establish dedicated-server, multiplayer, or real-GPU client acceptance.

## Status and scope

UG is under active development. Source-level work, automated tests, dedicated-server behaviour, and real-client behaviour have different verification requirements; no release or compatibility guarantee follows from this source upload. The project keeps world data, local test instances, raw logs, and internal collaboration records outside the published source tree.

## Upstream tooling and attribution

The initial Gradle structure came from CleanroomModTemplate and retains its existing `LICENSE` and copyright notice. The build uses a [custom Unimined fork](https://github.com/kappa-maintainer/Unimined) derived from [Unimined](https://github.com/unimined/Unimined). CHB, NeoFontRender UI Enhancements, and other dependencies remain separate projects; their code and artifacts are not vendored into UG.
