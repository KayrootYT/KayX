# Dual pane (2 swipe tabs) — copy / move between panes

Modelled on the screen recording `251218.mp4`.

## How it works
- Turn on **Dual pane** (bottom-bar icon in single mode, or Settings → "Dual pane by default").
- **Split view** (default): both panes side by side. The active pane has a thin accent line on top; tap a pane to activate it.
- **Swipe tabs**: each pane is full screen with a 2-tab strip on top; swipe left/right to switch.
  Toggle with the layout icon in the selection bar, the ⋮ menu, or Settings.
- Long-press a row to start selecting; tap more rows to add. Header shows `Folders / Files / Selected / Disk`.
- Selection bar: select all · invert · cancel · layout toggle · **actions menu**.
- Actions menu (two columns): Copy -> • · Move -> • · Delete · Rename · Tools · Compress · Properties · Share · Open with… · Add bookmark
  - **Tap Copy -> / Move ->**: confirm dialog "Do you want to copy the selected N files into <other pane folder>/ ?" → OK sends them to the other pane.
  - **Long-press Copy -> / Move ->** (the • marker): classic single-window clipboard; open the target folder and tap *Paste here*.
  - Rename on several items opens batch rename; Properties on several items shows totals.
- Normal bottom bar: back · forward (per-pane folder history) · + (new folder/file) · swap panes · up.
- Items that were just copied/moved show in green in the destination pane until you navigate.

## Files
- `feature/filebrowser/DualPaneBrowser.kt` — new UI (pager, rows, bottom bar, popup menu, Tools / multi-properties dialogs).
- `feature/filebrowser/FileBrowserViewModel.kt` — per-pane history, `transferSelectedToOther`, shared transfer/conflict flow, tab mode.
- `feature/filebrowser/FileBrowserScreen.kt` — routes to the new screen when dual pane is on; confirm-transfer dialog.
- `data/settings/SettingsStore.kt`, `feature/settings/SettingsScreen.kt` — persisted "swipe tabs" option.
- `test/.../DualPaneStateTest.kt` — state + transfer tests.

## Not verified here
No Gradle/Android SDK was available, so this was reviewed statically only: run `./gradlew testDebugUnitTest assembleDebug` (or push to CI).
