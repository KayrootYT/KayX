# KayX Offline File Assistant

The Offline Assistant is opened from the sparkle button beside the file-browser search field. It works in both single-pane and dual-pane browsing.

## Privacy and execution model

- Text commands are parsed locally by a deterministic, allow-listed Kotlin command grammar.
- No cloud model, API key, network request, analytics event, or document upload is used by this assistant.
- Voice input is optional and starts only after the user taps the microphone and grants Android's microphone permission.
- Command history is opt-in, stored only in local app preferences, capped at 20 entries, and can be disabled or cleared from the History control. Disabling history clears saved entries.
- Voice uses Android's on-device SpeechRecognizer API (Android 12/API 31+) only. KayX does not fall back to a network recognizer. If the device has no offline recognizer or language pack, the UI explains that and text commands remain available.
- On Android 8–11, text commands work; the Android on-device SpeechRecognizer API used here is unavailable.
- Commands never execute arbitrary shell text or model-generated code.

## Supported command families

- Search: `search photos`, `find report`, `search content invoice`
- Open/navigation: `open folder Download`, `open report.pdf`, `go back`, `go up`
- View: `show hidden`, `hide hidden`, `sort by size`, `refresh`, `toggle dual pane`
- Selection: `select all`, `select report.pdf`, `select pattern *.jpg`, `clear selection`, `invert selection`
- Transfers: `copy`, `cut`, `paste`
- File operations: `new folder Work`, `new file notes.txt`, `rename selected to notes-old.txt`, `delete selected`, `zip selected`, `extract archive.zip`, `share selected`, `undo`
- Storage/navigation helpers: `storage info`, `bookmark current`, `open recycle bin`, `open tags`, `open private space`, `open network storage`, `tag selected` (opens KayX's existing tag dialog)
- APK/archive tools: `inspect apk app.apk`, `browse archive backup.zip` (the named item must be visible in the current folder)
- Whole-storage inventory: `scan storage`, `show last scan`, `transfer status`, `acknowledge transfer recovery`, `resume scan`, `find large files`, `find old files`, `find duplicates`, `cancel scan`
- Category filters: `find photos` / `find images`, `find videos`, `find audio`, `find documents`, `find apks`
- Folder rollup: `folder size Download` (combines matching folder names across accessible roots; use a distinctive folder name)
- Organization preview: `organize images to /storage/emulated/0/Pictures/Sorted` and `move images to /storage/emulated/0/Pictures/Sorted` (both scan first and show a confirmation preview; move is local-to-local only)

Hindi and Hinglish aliases are provided for common navigation, selection, hidden-file, copy/cut/paste and search commands. The grammar is intentionally explicit; unsupported or ambiguous phrases are rejected instead of guessed.

## Safety boundaries

- Delete always opens KayX's explicit confirmation dialog, regardless of the general delete-confirmation preference.
- Rename and create operations open the existing name dialog with the requested name prefilled.
- Paste opens the existing destination confirmation; name conflicts continue through KayX's conflict resolver.
- Share uses Android's system share sheet. SAF items are not silently copied elsewhere.
- Operations still run through KayX's existing repository/SAF permission checks and undo journal where supported.
- The assistant can only open/select/extract items visible in the current folder listing. It does not silently crawl the entire phone for arbitrary commands.
- `organize CATEGORY to ABSOLUTE_FOLDER_PATH` is preview-first and copy-only: it lists the exact source files, total bytes and destination, and does not mutate anything until the user taps Confirm copy. It is capped at 100 files, uses KEEP_BOTH naming, revalidates source path/size/modified time and destination immediately before transfer, and never deletes originals.
- Sources may come from readable local storage or persisted SAF/SD-card/provider tree grants. SAF entries use tree-relative stable keys; the app rechecks the persisted grant, resolves the document again, verifies name/size/modified time and opens it for reading immediately before transfer. Copies may target an explicit writable local filesystem folder or a selected writable SAF/provider folder. SAF destination grants and the destination document are revalidated at confirmation.

## Whole-storage inventory (Phase 2 foundation)

- `scan storage` scans readable shared internal storage and persisted SAF tree grants, including user-granted SD-card/provider roots. A primary SAF tree is not scanned twice when the shared-storage filesystem root is readable.
- The scanner is read-only. It never moves, renames, or deletes files.
- Local shared-storage traversal writes an atomic, app-private checkpoint every 16 directories. After cancellation or process interruption, `resume scan` restores the last checkpoint and continues; if no compatible checkpoint exists it starts a fresh scan. A new `scan storage` always starts over. Checkpoints contain only local paths and file metadata, never contents. Checkpoint persistence is best-effort: a checkpoint write/size failure no longer aborts the read-only scan, and the result warns that interruption recovery may be unavailable.
- SAF/provider roots are rescanned from their root on resume because providers do not expose a portable durable directory cursor; local traversal progress is preserved.
- Traversal is bounded to 50,000 files and 24 directory levels. It checks coroutine cancellation between directories/files and reports scan progress, inaccessible roots, and whether the entry cap was reached.
- Android/data and Android/obb are excluded; Android/media remains eligible where the platform grants access.
- `find large files` reports files at least 100 MiB; `find old files` reports files not modified for at least 180 days.
- `find duplicates` reports SHA-256 duplicate candidates, not automatic deletion targets. Hashing is capped at 500 candidate files and 512 MiB total per scan. Files larger than the remaining hash budget are skipped. Hashing runs only for this explicit command, not for category or general inventory scans.
- Category filters use the scanned filename extension metadata and report up to eight names; supported groups are images, videos, audio, documents and APK packages.
- `folder size NAME` totals accessible files below every directory named NAME. If the same folder name exists in multiple locations, the output combines them and says so; it is a read-only estimate based on files visible to the bounded scan.
- Result summaries show at most eight names per command. The most recent completed inventory is also saved as a bounded (16 MiB maximum), app-private metadata snapshot; `show last scan` reloads it after an app restart. The snapshot contains paths, names, sizes, timestamps and category labels only—never file contents. Local entries are revalidated against their current path, size and modified time before being shown; stale entries are discarded. SAF entries are retained as stable keys but any later operation must re-resolve them and verify the persisted grant. Duplicate hash groups are deliberately not restored from the snapshot; `find duplicates` performs a fresh scan and hash pass.
- The local undo journal is crash-recoverable, validates primary and backup files before choosing one, and bounds reads/writes to 16 MiB and 10,000 records (the app uses a 50-operation history). A corrupt primary no longer causes a valid backup to be discarded.
- Duplicate review now offers a separate cleanup confirmation for local-only groups: every file is re-hashed and metadata/path revalidated, one copy is retained, and extras are moved into KayX Recycle Bin. Mixed SAF/provider groups are excluded. Preview-first move is available only for local sources to local destinations and uses the existing undo journal. Current limitations: durable per-directory SAF/provider cursors and provider-safe SAF move/delete organization actions remain unsupported because providers do not guarantee portable cursors or transactional restore. Transfer pause/resume/retry controls are tied to the active app session; the recovery record detects interrupted work but does not run transfers in the background or resume at byte level. Recycle-bin restore and other existing screens remain available through KayX's native UI; this assistant does not bypass their confirmation or permission gates. Organization copies are limited to 100 files per explicit confirmation.


- Foreground transfers now persist a bounded app-private recovery record before starting and update the completed-item count as work progresses. On process restart, a RUNNING record is marked INTERRUPTED; `transfer status` reports the saved plan and progress. A partial, cancelled, failed, or provider-warning transfer remains marked NEEDS_REVIEW. The assistant deliberately does not auto-retry: a provider may have committed a destination before source cleanup was interrupted, so blind retry could duplicate or damage data. Inspect source and destination before starting another operation. A new transfer is blocked while an unresolved recovery record exists; after manual inspection, explicitly say `acknowledge transfer recovery` to clear only the saved metadata (it does not modify files). This is durable interruption detection and manual reconciliation, not background WorkManager execution or byte-level resume.
