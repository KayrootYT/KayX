# KayX full-source audit — 2026-11-24

## Scope and method
- Reviewed the complete `main` Git tree (125 tracked files; tree was not truncated).
- Inspected file browser and dual-pane state, settings/session persistence, storage category lists, app manager, archive extraction/editing, filesystem search/repository, private-space vault, recycle bin, Android manifest, Gradle dependencies, CI workflows, implementation plan, and tests.
- This is a source-level review, not a substitute for Android device testing across API levels, OEM storage behavior, SD cards, root/Shizuku grants, or real archive/APK samples.

## Verified improvements included in this audit pass
1. Category file lists now expose search, clear-search, select-all, list/grid switching, and Date/Size/Name sorting.
2. All three category sort modes support both directions, including A–Z and Z–A.
3. Browser session persistence stores the last public/shared-storage primary folder, secondary folder, active pane, and dual-pane preference. Private app-data paths are excluded.
4. On launch, the browser restores the saved folder; dual-pane startup restores the secondary folder and active pane when saved paths still exist.
5. MediaStore results are merged with the bounded filesystem search index. A partial MediaStore hit no longer suppresses valid files missing from Android's index; a MediaStore query failure falls back to filesystem search.

## Important remaining gaps found in source

### P0 — storage correctness and safety
- SAF tree permissions are persisted by the picker, but the implementation plan confirms the main entry flow still requires all-files access and the main browser uses java.io.File. SD-card/SAF browsing and operations are not integrated end-to-end.
- Recursive copy/move/delete, symlink edge cases, very large folders, partial failures, and cancellation still need device-level validation.
- Durable background operations with notification progress, pause/resume, retry after process death, and user-visible cancellation are not yet a complete operation-queue workflow.
- Undo/redo for move/delete is not implemented as a durable journal.

### P1 — archives and APK workflows
- ZIP listing/editing/extraction is implemented. MultiExtract supports ZIP-like formats, 7z, tar, gzip, bzip2, and xz extraction.
- RAR appears in file-type categorization, but MultiExtract.canExtract() does not include .rar; RAR extraction is not implemented by the current extraction path.
- The ZIP repository explicitly documents that encrypted ZIPs need a dedicated library; encrypted archive workflows are not complete.
- The category screen opens only ZIP/JAR directly in the ZIP browser. Other supported extraction formats need a clear extract action/confirmation flow rather than routing into the ZIP-only viewer.
- The installed-app manager has search, User/System/All filters, sorting, inspect, backup/extract, app-info, launch, and uninstall actions. It is not a dedicated APK-file browser with installed/uninstalled grouping and APK-file metadata rows.
- Split APK backup exists, but split APK install/restore behavior still needs sample-based and device verification.

### P1 — browser completeness and performance
- Category-list search is local to already-loaded category results; global/cross-root search is a separate feature and is not yet integrated into this screen.
- Category-list search/sort/view state is transient; only the main browser folder/pane session is persisted.
- Thumbnail caching and bounded-memory preview generation remain outstanding.
- Large-directory and storage-scan performance needs profiling on low-memory devices.
- Tabs and pane navigation have unit-level state coverage, but full browser tabs/history restoration across process death is not implemented as a complete serialized session.

### P2 — tools and platform coverage
- Editor recovery/autosave, large-file safeguards, and robust encoding detection remain to be completed/verified.
- FTP client/local web server exist, but FTP/FTPS, SFTP, SMB, and WebDAV are not a complete secure network-storage suite. Credentials/host-key/certificate handling and resumable transfers need a dedicated audit.
- Root and Shizuku code exists; real-device grant lifecycle, command boundaries, and fallback behavior need device testing. Dhizuku is not implemented.
- Accessibility, reduced-motion behavior, contrast, font scaling, landscape/tablet layouts, and Android 13–16 compatibility need a structured device test pass.
- Release/minified build, signing configuration, native Rust ABI loading, and third-party license verification need release-candidate validation.

## Recommended implementation order
1. Integrate SAF tree grants into browser navigation and file operations without breaking normal shared-storage/root paths.
2. Build a dedicated APK-file browser: installed/uninstalled classification, package/version/icon/size/date metadata, filters, search, multi-select, install/share/delete/inspect actions.
3. Complete archive UX: format-aware open/extract routing, supported-format labels, explicit unsupported RAR/encrypted-archive messaging, and safe multi-extract confirmation.
4. Serialize browser session state safely (primary/secondary paths, dual-pane mode, active pane, tabs, per-pane history, sort/view/hidden settings), validating every restored path.
5. Add durable operation queue, cancellation/retry/notifications, and undo journal.
6. Add thumbnail cache and profile large-folder/search/preview workloads.
7. Add editor recovery and secure network protocol modules only with dedicated tests.
8. Run real-device matrix and release/minified/native ABI verification.

## Current CI status
The latest runs for commit 9d3f012ed1b03dac1bde6c9b0596c836f10181b2 completed successfully for Build & Test and Security Scan. Subsequent commits in this audit pass have their own CI runs; check GitHub Actions before treating latest HEAD as verified.

## Honesty boundary
No source-only review can guarantee zero bugs. This report distinguishes code present from behavior verified on a real device. Do not describe SAF, RAR, encrypted ZIPs, durable operation queue, undo/redo, or complete cross-device compatibility as finished until acceptance tests pass.