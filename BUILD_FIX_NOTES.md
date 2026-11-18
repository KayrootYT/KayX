# KayX update notes

## Changes in this delivery
- Fixed the supplied CI compiler error in `FileRepository.kt` by converting the nullable `Array<File>` to a list before merging additional root entries, then converting back to an array.
- Expanded file-type icon mapping for archives, images, audio, video, PDF, source code, text, fonts, office files, and APKs.
- Added animated selection tint and content-size transitions to file rows.
- Added dashboard card content-size transitions for expandable groups.
- Added repository unit tests for create/list/rename/search and unsafe-name/duplicate-name rejection.
- Added transfer tests for KEEP_BOTH conflict handling, progress callbacks, and copy-into-self protection.
- Added per-item copy/move progress to the file browser header, expanded file-format icon coverage, accessible icon descriptions, and long-press haptic feedback.
- Added explicit Compose animation and test dependencies.
- Updated CI to run unit tests, build the debug APK, verify the APK exists, and run on pushes, pull requests, and manual dispatch.
- Added `IMPLEMENTATION_PLAN.md`, `FREE_FEATURE_POLICY.md`, and updated `README.md`.

## Validation performed here
- The original build log was inspected and the reported compiler error was confirmed at `FileRepository.kt:21:37`.
- Static source assertions passed for the fix, animation imports/usages, transfer progress wiring, test dependency, and CI steps.
- CI workflow YAML parsed successfully.
- Final ZIP archive passed `unzip -t` integrity validation.

## Current verification
- `./gradlew assembleDebug --no-daemon` passed with JDK 17 and Android SDK 34.
- `./gradlew testDebugUnitTest --no-daemon` passed after fixing the stale transfer callback argument and missing `assertFalse` import.
- The local build skipped `cargoNdkBuild` because `cargo-ndk` is not installed; native Rust packaging and Rust tests were not verified.
- Device behavior for scoped storage, SAF browsing, Root/Shizuku/Dhizuku, network protocols, and Android 15/16 remains unverified. Follow-up work is documented in `IMPLEMENTATION_PLAN.md`.


Latest source hardening also adds PBKDF2-based private-space PIN storage with legacy migration and a canonical-path boundary check before cleanup deletion. JVM tests for those boundaries are included and passed in the current Gradle run.

The current follow-up provisions a Java 17 / Android SDK 34 Codespace, repairs two unit-test compilation errors found during the first full Gradle run, and moves PDF/image conversion off the UI thread. Conversion bitmaps are explicitly recycled and bounded to 16 MP per render.
