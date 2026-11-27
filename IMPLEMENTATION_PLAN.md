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
- [x] Add FTP/FTPS resumable downloads using a server-confirmed SIZE response, validated local offset, and REST before RETR; retain stable partial files only for FTP/FTPS and SFTP.
- [x] Add WebDAV resumable downloads using HEAD Content-Length, strong ETag or Last-Modified validators, If-Range, and strict 206 Content-Range validation before appending.
- [x] Add SMB resumable downloads using an opened-handle size/timestamp fingerprint persisted beside the partial file; reject changed/missing fingerprints and discard mismatched partials.
- [ ] Add protocol-safe resumable uploads; each needs protocol-specific offset/validator checks before retaining partial data.
- [ ] Add automated protocol integration tests against disposable FTP/FTPS, SFTP, SMB3 and WebDAV servers (local FTP REST resume test added; FTPS/SFTP/SMB3/WebDAV server coverage remains); add Android Keystore/device tests.
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


## Offline File Assistant roadmap (added 2026-11-27)

The user-facing assistant is a separate, fully local command feature. It is not the optional HTTPS provider assistant and must not depend on AI API keys or internet access.

### Phase 1 — Search-bar assistant and local commands (implementation in review)
- [x] Add a deterministic, allow-listed command parser with English/Hinglish/Hindi aliases; reject unknown and oversized commands.
- [x] Add assistant mode beside the existing file-browser search field in both single-pane and dual-pane layouts.
- [x] Wire navigation, filename/content search, hidden files, sorting, selection, copy/cut/paste, create, rename, delete confirmation, zip/extract, share, undo, bookmarks, dual-pane and storage summary commands to existing KayX flows.
- [x] Add optional microphone input using Android's on-device SpeechRecognizer only; no network recognizer fallback.
- [x] Request RECORD_AUDIO only after the user taps the microphone; add the permission to the APK review allowlist.
- [x] Add parser unit tests and privacy/command documentation.
- [ ] Run Build & Test, Android unit tests, debug/release APK verification and Security Scan on the final PR head.
- [ ] Validate text commands, microphone permission, offline language-pack availability and speech recognition on physical Android devices (no emulator required).
- [ ] Validate assistant commands against SAF/SD-card providers; unsupported provider actions must remain clearly unavailable.

### Phase 2 — Whole-storage intelligence and workflows (foundation implemented; follow-up remains)
- [x] Add a bounded, read-only inventory over readable shared internal storage and persisted SAF tree grants, including user-granted SD-card/provider roots; avoid duplicate primary-tree scanning when the shared root is readable.
- [x] Add offline commands for large files (100 MiB+), old files (180+ days), and SHA-256 duplicate candidates. Android/data and Android/obb are excluded; Android/media is included only where readable.
- [x] Add cancellable traversal with a 50,000-file / 24-level cap, progress callbacks, and inaccessible-root/truncation reporting.
- [x] Add offline category filters for images, videos, audio, documents and APKs, plus folder-size rollups by folder name (same-name folders are combined and clearly reported).
- [ ] Add byte-verified duplicate grouping and resumable/checkpointed scans; current duplicate results are bounded SHA-256 candidates and are never automatic deletion targets.
- [x] Add preview-first offline bulk copy organization with explicit destination, exact file/size preview, separate confirmation, stale-source revalidation, KEEP_BOTH conflicts, and a 100-file cap; original files are never deleted.
- [ ] Extend organization to SAF/provider roots and add separately confirmed move/delete actions only after provider-safe revalidation and recovery tests.
- [ ] Add safe command history and user-controlled local preferences; no telemetry or cloud memory.
- [ ] Add broader command coverage for tags, recycle bin, archives, APK tools, private space and network-storage screens, respecting each screen's existing permissions.

Phase 2 current limits: scan results are in-memory only; up to 50,000 entries/24 levels are inspected, duplicate hashing is capped at 500 files/512 MiB, and confirmed organization copies are limited to 100 local files per operation. SAF bulk organization and move/delete remain unavailable.

## AI expansion roadmap (added 2026-11-27)

AI is an optional extension and must not weaken KayX's offline-first/free product rules. The first AI foundation is being delivered separately from existing file-manager phases.

### AI Phase 1 — Provider foundation (merged; CI green)
- [x] Add provider-neutral configuration with HTTPS-only endpoint validation.
- [x] Add Android Keystore AES-256-GCM storage for API credentials; never persist plaintext keys.
- [x] Add a provider-neutral chat-client contract and bounded OpenAI-compatible Chat Completions transport.
- [x] Add Settings entry for optional endpoint/model/key configuration; AI remains disabled by default.
- [x] Add focused configuration validation unit tests.
- [x] Build & Test, debug APK artifact verification and Security Scan passed on merge commit `7923e7d` (2026-11-26).
- [ ] Validate credential save/read/clear and provider requests on a physical Android device with a test endpoint.

### AI Phase 2 — Function calling and tool registry (merged; CI green)
- [x] Add OpenAI-compatible strict function definitions and bounded tool-call response parsing.
- [x] Add a strict app-code allow-listed registry with typed argument validation and unknown-key rejection.
- [x] Separate read-only and destructive tools; destructive tools require a separate explicit confirmation callback.
- [x] Add per-tool permission checks, bounded timeouts, coroutine cancellation propagation and sanitized failures.
- [x] Add metadata-only audit events; no arguments, prompts, credentials or tool results are logged by the registry.
- [x] Add JVM tests for unknown tools, malformed/missing/oversized arguments, denied permissions, confirmation gates, success, timeout and sanitized exceptions.
- [x] Build & Test (including unit tests, APK build and native-library artifact verification) and Security Scan passed on final PR head `88b02f3` (2026-11-26).
- [ ] Physical-device validation of provider function-call compatibility and real confirmation UI.
- Note: no production file-operation tools are registered in this phase; registration requires a separately reviewed app implementation.

### AI Phase 3 — Document intelligence (RAG; follow-up in progress)
- [x] Add an explicit Android document picker; only user-selected files are read.
- [x] Add bounded UTF-8 text ingestion (2 MB / 120,000 characters per file), chunking, and in-memory-only document state.
- [x] Add deterministic local lexical retrieval with source filename and chunk references; no embeddings or network are used.
- [x] Add a fail-closed share gate that refuses to construct remote context unless explicit consent is supplied; cap outgoing context at 24,000 characters.
- [x] Add clear unsupported-format, invalid-encoding, binary-content and read-permission error handling. PDF and Office formats are explicitly unsupported in this first text-only slice; the follow-up adds bounded PDF and OOXML text extraction.
- [x] Add JVM tests for local retrieval, result bounds, source references, explicit consent, prompt framing and outgoing-context bounds.
- [x] Integrate the consent gate into an actual per-request AI send flow: show the exact selected source names and excerpt count, require a separate confirmation for every send, and send only locally retrieved bounded excerpts to the configured HTTPS provider.
- [x] Frame document excerpts as untrusted reference data in the system prompt; never execute tool calls returned from document-question requests.
- [x] Add bounded local text extraction for PDFs (PDFBox Android), DOCX, XLSX and PPTX; reject encrypted/password-protected PDFs, legacy binary Office formats, malformed/unsafe Office XML, oversized archives and unsupported content.
- [x] Add JVM fixture tests for DOCX paragraphs, PPTX slides, XLSX shared/inline cells and DTD/external-entity rejection.
- [x] Add Android instrumentation fixtures for normal text PDFs, password-protected PDFs, image-only/empty-text PDFs and over-200-page PDFs; compile these tests in PR CI.
- [ ] Execute the Android PDF instrumentation fixtures on a physical Android device and verify PDFBox initialization.
- [x] Run Build & Test, APK artifact verification and Security Scan on final PR head `8b32b38` (2026-11-27); Release/minified APK build passed.
- [ ] Physical-device validation of Storage Access Framework picker and provider consent behavior.

### AI Phase 4 — Semantic search
- [ ] Add an on-device vector index and embedding-provider abstraction.
- [ ] Keep filename search available offline and retain exact-match fallback.
- [ ] Add index lifecycle, deletion synchronization and storage-budget controls.

### AI Phase 5 — Agent and MCP
- [ ] Add bounded agent orchestration only after the tool registry is stable.
- [ ] Add MCP client support with explicit server trust and tool consent.
- [ ] Prevent untrusted document text and tool output from changing authorization policy.

### AI Phase 6 — Memory, workflows and guardrails
- [ ] Add opt-in, user-visible memory with clear/reset controls.
- [ ] Add resumable, cancellable workflows with progress and confirmation checkpoints.
- [ ] Add prompt-injection defenses, output validation, rate limits and privacy/security tests.

AI phase completion requires green CI on the final PR head and device validation for Android-specific behavior. No phase is complete merely because a source file exists.
