# KayX Offline File Assistant

The Offline Assistant is opened from the sparkle button beside the file-browser search field. It works in both single-pane and dual-pane browsing.

## Privacy and execution model

- Text commands are parsed locally by a deterministic, allow-listed Kotlin command grammar.
- No cloud model, API key, network request, analytics event, or document upload is used by this assistant.
- Voice input is optional and starts only after the user taps the microphone and grants Android's microphone permission.
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
- Storage/navigation helpers: `storage info`, `bookmark current`
- Whole-storage inventory: `scan storage`, `find large files`, `find old files`, `find duplicates`, `cancel scan`

Hindi and Hinglish aliases are provided for common navigation, selection, hidden-file, copy/cut/paste and search commands. The grammar is intentionally explicit; unsupported or ambiguous phrases are rejected instead of guessed.

## Safety boundaries

- Delete always opens KayX's explicit confirmation dialog, regardless of the general delete-confirmation preference.
- Rename and create operations open the existing name dialog with the requested name prefilled.
- Paste opens the existing destination confirmation; name conflicts continue through KayX's conflict resolver.
- Share uses Android's system share sheet. SAF items are not silently copied elsewhere.
- Operations still run through KayX's existing repository/SAF permission checks and undo journal where supported.
- The assistant can only open/select/extract items visible in the current folder listing. It does not silently crawl the entire phone for arbitrary commands.

## Whole-storage inventory (Phase 2 foundation)

- `scan storage` scans readable shared internal storage and persisted SAF tree grants, including user-granted SD-card/provider roots. A primary SAF tree is not scanned twice when the shared-storage filesystem root is readable.
- The scanner is read-only. It never moves, renames, or deletes files.
- Traversal is bounded to 50,000 files and 24 directory levels. It checks coroutine cancellation between directories/files and reports scan progress, inaccessible roots, and whether the entry cap was reached.
- Android/data and Android/obb are excluded; Android/media remains eligible where the platform grants access.
- `find large files` reports files at least 100 MiB; `find old files` reports files not modified for at least 180 days.
- `find duplicates` reports SHA-256 duplicate candidates, not automatic deletion targets. Hashing is capped at 500 candidate files and 512 MiB total per scan. Files larger than the remaining hash budget are skipped.
- Result summaries show at most eight names per command. The inventory is in-memory only; no paths, file content, or scan history are uploaded or persisted.
- Current limitations: scan resume/checkpoint persistence, category-specific filters, folder-size rollups, preview-first bulk organization, and broader cross-screen commands are still pending. No bulk action is performed by these commands.
