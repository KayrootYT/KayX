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

## Not verified in this environment
- A full Gradle/Kotlin/Android build and the unit tests were not executed because this environment does not have a Gradle executable/wrapper or Android SDK installed.
- Root, Shizuku, Dhizuku, FTP/FTPS, SFTP, SMB, WebDAV, and full Android 15/16 behavior are not claimed as implemented. Their follow-up work is documented in `IMPLEMENTATION_PLAN.md`.


Latest source hardening also adds PBKDF2-based private-space PIN storage with legacy migration and a canonical-path boundary check before cleanup deletion. These changes have static/source review and JVM test coverage added, but the test suite has not been executed because the Android/Gradle toolchain is unavailable in this environment.
