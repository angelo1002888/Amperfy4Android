# Contributing

Thanks for your interest in Amperfy4Android. This page covers the build setup, the test
gates and the rules that are enforced in this code base.

## Environment

- JDK 17
- Android Studio (latest stable); the project uses AGP 9.2.1 with its built-in Kotlin
  2.2.10 and the Gradle 9.4.1 wrapper
- Android SDK with API 35 installed; minimum supported API is 26
- A device or emulator for instrumentation tests (JVM unit tests do not need one)

## Build and test

```bash
./gradlew assembleDebug            # build
./gradlew testDebugUnitTest        # JVM unit tests
scripts/run-gates.sh --unit-only   # static boundary checks + JVM unit tests (no device needed)
scripts/run-gates.sh               # full gate: adds connectedDebugAndroidTest on a connected device
```

Please run at least `scripts/run-gates.sh --unit-only` before opening a pull request.
Changes to the database or repositories should also pass the full gate on a device.

## Code boundaries (enforced by `scripts/run-gates.sh`)

- `import androidx.room` is allowed only under `app/src/main/java/com/amperfy/data/local/db/`.
  Everything else goes through the `data/local/store/` boundary interfaces.
- Icons are used exclusively through `ui/theme/AmperfyIcons.kt`. Do not import
  `androidx.compose.material.icons` in business code. Do not commit SF Symbols path data.
- Room schema changes require: bumping the version in `AmperfyDatabase.kt`, an explicit
  `Migration`, a migration test, and the regenerated JSON under `app/schemas/`.
  `fallbackToDestructiveMigration` is forbidden.
- The audio chain must keep its PCM path: never enable audio offload or passthrough, the
  EQ / ReplayGain / visualizer processors in `player/audio/` depend on it.

## iOS alignment

This project is a port of Amperfy for iOS, baseline release 2.1.0
(https://github.com/BLeeEZ/amperfy, tag `2.1.0`).

- Keep class, setting and menu names aligned with the iOS code so both projects can be
  cross-referenced.
- Behaviour follows iOS 2.1.0 unless there is a good reason not to. Deliberate deviations
  must be explained in the pull request and in a comment next to the code.
- When behaviour lives inside closed-source framework internals, verify it on a real iOS
  device instead of guessing.

## Commit messages

- English, one line summary in the form `type: summary` (`feat`, `fix`, `docs`, `chore`,
  `build`, `refactor`, `test`), followed by a body explaining motivation and key points.
- `fix` commits must state the root cause.
- Do not add `Co-Authored-By` lines and do not bypass git hooks.

## Language

Existing code comments are largely written in Chinese; that is fine to leave as is. New
code comments, commit messages and documentation should be written in English. Issues and
pull requests are welcome in English or Chinese.

## License

By contributing you agree that your contributions are licensed under the GPLv3, the same
license as the project.
