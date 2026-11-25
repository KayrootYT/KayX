# KayX — iOS Glass / Complete Edition

## Product rules

- All local features are free; no VIP, subscription, purchase, or remote feature-unlock path.
- Offline-first: file browsing and local tools must work without an account or application server.
- No analytics, advertising SDK, or unnecessary telemetry is to be added.
- Privileged operations must use Android's real user-granted capabilities (SAF, root, Shizuku, etc.); never simulate elevated access.
- Network file protocols, if implemented, connect directly to user-selected servers and must not be required for local functionality.

## Source inventory (from the supplied KayX project)

### Existing source modules

- File browsing: `FileBrowserScreen`, `FileBrowserViewModel`, `FileRepository`, `FileOps`, `ExternalOps`, `RecycleBin`.
- Home, settings, storage: `HomeScreen`, `SettingsScreen`, `StorageScreens`, `StorageScanner`, `TagsAndPrivate`.
- App/APK and reverse-engineering utilities: `AppManagerScreen`, `ApkRepository`, `ApkSignRepo`, `AxmlDecoder`, `DexRepository`, `DexAssemblyRepository`, `SmaliRepository`.
- Archive and integrity tools: `ArchiveRepository`, `ArchiveEdit`, `MultiExtract`, `ChecksumRepository`.
- Editor/media/system utilities: `EditorTools`, `SyntaxHighlighter`, `MediaTools`, `SystemTools`.
- UI foundation: `Theme`, `Glass`, `IosComponents`.

A source module is not proof that every workflow is complete or works on every Android version. Each item below needs runtime verification.

## Delivery phases and acceptance criteria

### Phase 1 — Build and correctness (current)
- [x] Repair the reported Kotlin type error in `FileRepository.kt` by normalizing the nullable `Array<File>` to a list before merging, then converting to an array.
- [x] Add focused repository unit tests for create/list/rename/search and unsafe-name rejection.
- [x] Add transfer tests for KEEP_BOTH conflict handling, progress reporting, and rejecting copy-into-self.
- [x] CI now runs unit tests, builds the debug APK, and verifies the APK artifact on pushes, pull requests, and manual runs.
- [ ] Run CI on GitHub and resolve any newly surfaced compiler/test errors.
- [ ] Add device tests for scoped storage, SD cards, SAF, and Android 13–16 behavior.
- [ ] Integrate persisted SAF tree grants into browsing and file operations. The current picker stores permissions, but the main UI still gates entry on all-files access and the file browser uses `java.io.File`.

### Phase 2 — iOS 26 visual system (current partial)
- [x] Retain the existing iOS-inspired grouped surfaces, large title components, segmented controls, light/dark/AMOLED palettes, and glass cards.
- [x] Add subtle content-size animation to dashboard groups and file rows; animate file-icon tint when selection changes.
- [x] Add long-press haptic feedback and meaningful file-type accessibility descriptions.
- [ ] Unify navigation transitions, sheets, context menus, haptics, and reduced-motion behavior.
- [ ] Verify contrast, font scaling, TalkBack labels, and landscape/tablet layouts.

### Phase 3 — File manager completeness
- [x] Source exists for browsing, sorting, search, multi-select, file operations, recycle bin, bookmarks, tags, and private space.
- [x] File-list icons cover expanded archive, image, audio, video, PDF, source, text, font, office, ebook, database, disk-image, and APK formats.
- [x] Copy/move now exposes item-level progress in the browser header.
- [ ] Validate copy/move/delete conflict handling, partial failures, symlinks, large folders, and cancellation.
- [ ] Add durable operation queue with progress, pause/resume, retry, and notification actions.
- [ ] Add undo/redo journal and duplicate/large-file workflows with safe deletion confirmation.
- [ ] Add thumbnail caching and bounded-memory media previews.
- [ ] Add cross-root global search, grid/list mode, settings backup/restore, persistent operation notifications, and undo for move/delete.

### Phase 4 — Advanced Android access
- [x] Replace the current Shizuku placeholder with the official Shizuku API (api/provider 13.1.5, user service via AIDL) and the explicit Shizuku permission flow. Root explorer can use Shizuku as its backend (Settings → Advanced access). Not yet build-verified on a device.
- [ ] Implement and test Dhizuku only where its supported API and device-owner requirements permit it.
- [ ] Make root detection and root command execution separate states; request root only after a user starts a privileged action.
- [ ] Keep SAF and normal storage paths fully functional when privileged access is unavailable.

### Phase 5 — Archive, APK, editor and media
- [x] Source modules exist for archive operations, APK inspection/signing, DEX/Smali, XML decoding, checksums, text/code editing, and media viewing.
- [ ] Verify supported archive formats and encrypted archive behavior against real sample files.
- [ ] Verify split APK install flows and package-installer permission handling.
- [ ] Add editor recovery/autosave, large-file safeguards, and encoding detection.
- [ ] Verify media codecs and unsupported-format messaging.

### Phase 6 — Network storage
- [x] Add independent FTP/explicit FTPS, SFTP, SMB2/3, and WebDAV clients; expose all four protocols in the Network Storage browser.
- [x] Store remembered network passwords through the Android Keystore AES-GCM credential store; saving is opt-in and credentials are not logged.
- [x] FTPS verifies TLS certificates and hostnames with no downgrade; SFTP requires a verified OpenSSH `known_hosts` file and strict host-key checking; WebDAV requires HTTPS, rejects redirects, and hardens XML parsing against XXE; SMB requires SMB3 encryption and signing.
- [x] Add bounded remote listing/path validation and a user-visible cancel action for active transfers.
- [x] Keep protocols optional and connect directly to user-selected servers; no KayX proxy/server dependency.
- [x] Add verified-offset resumable SFTP downloads: interrupted partial files are retained, remote size is checked before resuming, and progress continues from the existing byte offset.
- [ ] Add resumable FTP/FTPS downloads (REST), SMB downloads, WebDAV Range downloads, and protocol-safe resumable uploads; each needs protocol-specific offset/validator checks before retaining partial data.
- [ ] Add automated protocol integration tests against disposable FTP/FTPS, SFTP, SMB3 and WebDAV servers; add Android Keystore/device tests.
- [ ] Verify the new clients and unified UI with the full Android build, security scan, and physical-device testing before marking Phase 6 complete.

### Phase 7 — Performance, accessibility and release
- [ ] Profile large directories, search, storage scans, and thumbnails on low-memory devices.
- [ ] Add cancellation and bounded concurrency to all recursive/background work.
- [ ] Add crash-free smoke tests, accessibility checks, and release/minified build verification.
- [ ] Verify APK permissions and third-party license notices before release.

## Current delivery boundary

The current follow-up adds item-level copy/move progress, expanded file-type icon/accessibility mapping, long-press haptics, and transfer-focused unit tests. Progress is per top-level selected item; it is not a byte-level progress meter and does not yet support pause/resume or cancellation.

This update repairs the specific build-log compiler error, improves file-type icon coverage and selection animation, adds dashboard content-size transitions, introduces repository unit tests, and strengthens CI. It does **not** claim that Root/Shizuku/Dhizuku, FTP/SFTP/SMB/WebDAV, durable background queues, undo/redo, or complete Android 15/16 compatibility are implemented; these remain explicit follow-up phases. A full Android build cannot be certified until the updated GitHub Actions workflow runs successfully or an Android SDK/Gradle environment is available.


## Latest hardening pass
- [x] Upgrade private-space PIN storage to salted PBKDF2-HMAC-SHA1 (120,000 iterations) with legacy SHA-256 migration after successful verification.
- [x] Run PIN derivation and verification on `Dispatchers.IO` rather than blocking Compose UI.
- [x] Guard cleanup deletion to regular files canonically contained within shared storage; reject external symlink targets and duplicate paths.
- [x] Add JVM tests for PIN derivation and cleanup deletion boundaries.
- [ ] Validate the changes with a real Gradle/Kotlin build and Android device tests; this environment does not currently have the Android build toolchain.


## Recycle-bin safety follow-up
- [x] Validate recycle-bin entry IDs before resolving payload paths; reject traversal and path separators.
- [x] Validate indexed original paths remain within shared storage and outside the recycle-bin directory.
- [x] Apply the same original-path validation before trashing and restoring entries; reject symlinks resolving outside shared storage.
- [x] Add JVM tests for traversal IDs, out-of-root paths, recycle-bin self-reference, and escaping symlinks.
- [ ] Run the new tests in Android Gradle CI and verify restore/trash behavior on device storage.


## File-browser follow-up (2026-11-15)

- [x] Separate left/right pane selection state and route selection actions to the active pane.
- [x] Preserve independent selection visuals in dual-pane mode.
- [x] Support cross-pane copy/move using the existing clipboard and active destination pane.
- [x] Add explicit MIME mappings and tap-to-open routing for PDF, Microsoft Office, and OpenDocument formats.
- [x] Route APK taps directly to Android's package installer flow.
- [ ] Verify on a real Android device with installed PDF/Office handlers and unknown-app installation permission.
- [ ] Run Gradle compilation and unit tests in an Android SDK environment.


## Polish & hardening pass (2026-11-15)

- [x] Navigation state (open folder, Settings, tool stack) now survives configuration changes via `rememberSaveable`; the activity also handles rotation/size/font-scale changes without recreation so unsaved editor text isn't lost on rotate.
- [x] Manifest: `allowBackup=false` plus `data_extraction_rules.xml` so private-space PIN records, tags and settings are never cloud-backed-up or device-transferred; `supportsRtl`, predictive-back opt-in, `adjustResize` for the keyboard.
- [x] Accessibility: TalkBack labels on previously unlabeled toolbar/selection/back buttons in the file browser and every tool screen.
- [x] Deprecations: directional icons moved to `Icons.AutoMirrored.Filled.*` (correct in RTL); `Divider` replaced by `HorizontalDivider`/`VerticalDivider`.
- [x] Home: the toolbar button that opens the file browser now uses a folder icon instead of a misleading search icon.
- [ ] **Not yet compiled or run.** These edits were made without a Gradle/Android SDK; run `gradle testDebugUnitTest assembleDebug` (or push to trigger CI) before release.
- [x] CI log (2026-11-15): fixed `FileBrowserScreen.kt` type mismatch — `ExternalOps.isOfficeOrPdf` takes a `File`, callers passed a `FileItem` (3 call sites). Migrated deprecated `kotlinOptions` to `compilerOptions`.

## Update: Shizuku, 3D themes, file time
- Shizuku: `data/advanced/ShizukuShell.kt` + `ShizukuUserService.kt` + `aidl/.../IUserService.aidl`; `RootShell.run` routes to Shizuku when Settings → "Root explorer uses" is Shizuku.
- Backgrounds: Settings → Appearance → Background (Off / Glow / Aurora / Orbs / Cubes). Animated styles are drawn in the draw phase only.
- 3D icons: `ui/Icon3D.kt` (`Tile3D`, `Icon3D`), toggle in Settings → Appearance.
- File/folder rows now show size · date **and time** (`ui/Format.kt`).

## Update: gestures + smart folder icons
- Row swipe-select now drags the row itself; selects at 70 % of the row width (`SwipeSelect.kt`).
- Long-press timeout 650 ms app-wide (Theme.kt).
- Pull-down: dual pane = short pull selects that pane, long pull also refreshes; single pane = refresh only (`PullHost`).
- Folder icons follow content (`ui/FolderKind.kt`, toggle in Settings).
