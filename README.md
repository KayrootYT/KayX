# KayX

KayX is an offline-first Android file manager and utility toolkit built with Kotlin and Jetpack Compose.

## Build

- JDK 17
- Android SDK matching `compileSdk` in `app/build.gradle.kts`
- Gradle 8.9

```bash
gradle testDebugUnitTest --no-daemon
gradle assembleDebug --no-daemon
```

The GitHub Actions workflow runs unit tests, builds a debug APK, and uploads it as an artifact.

## Product direction

KayX follows an iOS-inspired glass/grouped UI while remaining a native Android app. The browser includes expanded file-format icon/accessibility coverage, selection haptics, and item-level copy/move progress. All local functionality is intended to remain free and offline-first. See `FREE_FEATURE_POLICY.md` and `IMPLEMENTATION_PLAN.md` for product constraints, source inventory, implementation status, and remaining work.

## Verification note

A source module does not guarantee a complete feature. Privileged access, network protocols, storage behavior, and Android-version compatibility require device testing. The current implementation status and known gaps are documented in `IMPLEMENTATION_PLAN.md`.


### Latest hardening update
- Private-space PINs now use salted PBKDF2-HMAC-SHA1 derivation (120,000 iterations) with constant-time comparison; existing SHA-256 PIN records are upgraded after a successful unlock. PIN work runs off the UI thread.
- Cleanup deletion now rejects paths outside shared storage and symlinks that resolve outside it; duplicate selections are deduplicated before deletion.
- Added JVM tests for PIN derivation and cleanup path safety.


## Latest file-browser fixes

- Dual-pane selection is isolated: each pane keeps its own selected paths, and toolbar actions use only the active pane.
- Copy/Cut stores the active pane selection in the clipboard; switching panes and pasting targets the active pane directory.
- PDF and common Microsoft/OpenDocument formats use explicit MIME types and open through Android's installed document viewer. A compatible viewer app is required.
- Tapping an APK opens Android's package installer; Android may first require KayX's “Install unknown apps” permission.

## Latest UI update

- **Long press** on a file/folder now opens an item popup (Select, Open, Open with…, Copy, Cut, Rename, Delete, Compress, Extract, Share, Copy path, Bookmark, Properties) instead of starting selection. Selection is still available by swiping a row (now at **50 %** of the row width), from the popup's *Select*, or by tapping once something is selected.
- **Home**: *Quick access* now sits above *Recent*.
- **Open with…** has Built-in / System suggestion / Text / Image / Video / Audio / All / Actions tabs, search, a help page, and long-press on an icon sets the default opening method for that extension (manage them from the help dialog).
- **Web functions** (HTML): choose the website root directory, see the preview path, pick what tapping an HTML file does (Ask / Edit / Preview). Preview is served from a loopback-only HTTP server so root-relative links work.
- **Text editor menu**: File ▸, Search, Syntax, Previous/Next position, Jump to line, Soft wrap, Read-only mode, Smooth mode, Code completion, Preferences, Close file.
- **Apps**: filter/sort chips scroll horizontally so the last chip is no longer clipped.
