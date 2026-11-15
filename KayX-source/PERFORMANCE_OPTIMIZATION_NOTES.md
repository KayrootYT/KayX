# KayX performance optimization notes

## Changes in this build

- Filename search now traverses every nested directory instead of stopping at depth 4.
- Search builds a breadth-first in-memory file index once per directory/hidden-files setting, then filters that index for subsequent queries.
- The index is short-lived (15 seconds), limited to two directory roots, and explicitly invalidated after KayX file operations, transfers, extraction, and archive rewrite.
- The recursive index builder avoids revisiting canonical directory paths (symlink-loop protection), skips virtual kernel filesystems when searching from `/`, and checks coroutine cancellation while traversing, so typing a new query or navigating away can stop obsolete work.
- Search input debounce reduced from 280 ms to 120 ms.
- Dual-pane folder contents load concurrently instead of waiting for the first pane before starting the second.
- Settings description updated to reflect unlimited nested filename search.

## Verification

- Compiled `FileRepository.kt` and its model dependency with Kotlin/JVM compiler.
- Ran a smoke test proving search finds a file six directories deep, reuses the warm index, and refreshes after explicit invalidation.
- Full Android Gradle build was not run in this environment because this source ZIP does not include a Gradle wrapper and no system Gradle installation is available.

## Expected behavior

The first search in a directory still needs to enumerate that directory tree. Subsequent query edits against the same directory should be much faster while the 15-second index remains valid. A very large or slow external-storage tree can still make the first scan take time; Android filesystem access speed and storage permissions also affect that initial scan.
