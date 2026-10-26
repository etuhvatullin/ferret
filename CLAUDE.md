# Ferret — a SQL console for macOS and Windows (Java)

Swing + FlatLaf + RSyntaxTextArea, every database through JDBC, Java bundled into the app (jpackage). Its predecessor
is a macOS-only Swift app: Ferret reads that app's data as is (the same JSON files in the data folder and
`passwords.enc` — AES-GCM like CryptoKit: nonce 12 ‖ ciphertext ‖ tag 16), so any data file format may only change in
a backwards-compatible way. Local developer notes (machines, private endpoints) live in `CLAUDE.local.md`, which is not
in git. Code comments are in Ukrainian.

## Rules

- Passwords live only in Ferret's own encrypted file (`core/Passwords`), key next to it. Don't use OS keychains.
- Database drivers are not bundled: the app downloads them from Maven Central at runtime and verifies checksums.
  Never bundle Oracle / MySQL drivers (per-user licenses).
- `update.json` (update URL; for a private channel also a read-only key) is never committed: `release.sh` writes it
  before packaging.
- Behaviour follows DataGrip; the code is our own.
- Menus: File · Edit · Query · Connections · Help — each item under the heading that names it (Query holds only
  run/stop). `Ctrl+,` / `⌘,` is Settings, `Ctrl+Shift+,` — Connections. On macOS "About" and "Settings…" live in
  the app menu (`Desktop.setAboutHandler` / `setPreferencesHandler` — Java can't add other items there).
- **Every user-visible text goes through `I18n`** (see Languages below) — no string literals in the UI.
- Questions and messages — `ui/Ask` (`confirm` / `inform` / `input`), not `JOptionPane`: its buttons are stretched to
  one width and say "OK" / "Cancel" in English in every language.

## Commands

The script headers are the source of truth; in short:

- `./build.sh` — libraries (sha1-checked), compile, headless tests (`ferret.tests.All`), `build/ferret.jar`.
  `./build.sh fast` — no tests (and wipes `build/test-classes`). `./build.sh ui` — plus `ferret.ui.UiTests`.
- `./package.sh win | mac` → `dist/`. `./release.sh "notes"` — a GitHub release.
- Live databases in tests — `FERRET_TRINO_URL`, `FERRET_CH_URL`, `FERRET_PG_URL` (`trino://user:pass@host:port/catalog`);
  run ClickHouse / Postgres in a throwaway Docker container and remove it afterwards.

## Layout

`core/` — no Swing: models and file formats (`Model`, `Json`, `Store`), passwords, drivers (`Drivers` — download,
`Jdbc` — queries, tree, read-only guard, human-readable network errors), SQL text (`SqlText`, `Format`, `Dialect`),
result values (`Values`), `I18n`, `Updater`. `ui/` — Swing: `AppState` (state and `emit` events), `MainWindow`,
`EditorPanel`, `ResultPanel`, `Sidebar`, `TabBar`, `ConnectionsDialog`, `SettingsDialog`, `AboutDialog`, `Help`
(Help menu: shortcuts built from the menu itself, bug report, links), `SuggestionPopup`, `Splash`, `UpdateBanner`,
`Theme` / `Ui` ("Nora" colours, drawn icons — never emoji as icons). New non-UI logic goes to `core/` with a test in
`src/test/java/ferret/tests/`.

## Languages

English (default), Ukrainian, Russian, German, Spanish — `src/main/resources/ferret/i18n/messages*.properties`
(UTF-8; `messages.properties` is English). First launch follows the system language if it is one of the five,
otherwise English; the choice is `settings.json` → `language` (**Settings**, `Ctrl+,`, then a restart).

- `I18n.t("area.name", args…)` — MessageFormat only when args are given; then a literal apostrophe is `''`.
- Plurals — `I18n.plural("key", n)`: `key.one / key.few / key.many` in Ukrainian and Russian, `key.one / key.other`
  elsewhere. Numbers — `I18n.num(n)`.
- A new text needs a key in **all five** files with the same `{0}…`: `I18nTests` fails otherwise.
- The test suite runs in Ukrainian (`I18n.use("uk")`) because many assertions and UI-test lookups use Ukrainian texts.
- SQL function descriptions: `fn.<name>` or `fn.<dialect>.<name>`, resolved lazily.

## Testing the window

Only `./build.sh ui` proves keyboard behaviour: real key presses and clicks via `Robot` (in WSL — WSLg, `DISPLAY=:0`,
a separate X server, so keys never reach Windows apps). Events dispatched straight into the editor bypass menus and
focus and can't see shortcut conflicts — test every new shortcut with a real key press. Never run `Robot` on a
Windows desktop: if the window lacks focus, `Ctrl+W` closes a tab in someone else's app.

Snapshot: `FERRET_SNAPSHOT=file.png` (+ `FERRET_DATA_DIR` pointing at a temp folder); `FERRET_SNAPSHOT_PAINT=1` makes
the app paint itself and open off-screen (on macOS `Robot` without the Screen Recording permission silently returns
black; modal dialogs aren't part of the main window's `paint`). Before a Windows snapshot check what is in the
foreground: in a full-screen game `Robot` captures the game and WSLg windows get no focus.

## Pitfalls (measured)

- **The suggestion list is a `JLayeredPane` layer of the main window, not a `JWindow`.** While a separate window
  appeared/disappeared Linux dropped key presses ("l, Tab, .sa" became `lake` or `   .sa`; `Ctrl+T` after Esc vanished).
- **RSyntaxTextArea takes `⌘[`** for "matching bracket" — so `⌘[` / `⌘]` are bound in the editor itself.
- **Under FlatLaf the editor types the character of a ⌘ / Ctrl shortcut** (`⌘/` commented and also typed "/";
  `Ctrl+1…9` typed a digit). `keyTyped` in `EditorPanel` swallows those, except Ctrl+Alt (AltGr on Windows).
- **jpackage** builds only for its own OS; on macOS the first version number must be ≥ 1; macOS — `codesign -s -`
  (no developer ID: first launch needs "Open Anyway").
- **Trino:** listing a whole catalog is slow and fails on the `system` schema → `Jdbc.tables` goes schema by schema in
  parallel.
- **ClickHouse JDBC:** fails without `slf4j-api` (`Drivers.Spec.extras`). 0.11.0 can't read `Array(Nullable(UInt64))`
  — it is in `Drivers.BROKEN` (never offered; an installed one is replaced on the next connection); 0.10.0's own LZ4
  can't read 26.x servers → `client.use_http_compression=true`. Run the live test against a fresh server (its table
  has an array column).
- **`.cmd` files must have Windows line endings (CRLF, `.gitattributes`)** and `chcp 65001` first: otherwise cmd drops
  lines (empty install path) and breaks non-ASCII paths. Guard `robocopy /MIR` against an empty target.
- **`start` with a `.cmd` opens `cmd /k`** — a window stayed after updating. Launch with `start "" /min cmd /c …` from a
  folder outside the app; the work folder removes itself (`(goto) 2>nul & rd /s /q …`), leftovers — `Updater.cleanupOld()`.
  Test updates offline: a copy whose `update.json` points to `http://127.0.0.1:8765/latest.json`, then
  `Updater.install` with `-Djpackage.app-path`.
- A jpackage app on Windows is two `Ferret.exe` processes (launcher and Java); that's normal.
- **Windows menu — FlatLaf's embedded menu bar (`setJMenuBar`), the wordmark is a "glue" inside it (`titleMark`).**
  With `FULL_WINDOW_CONTENT` the menu became a separate row, and moved into a title strip it only worked every other
  click (FlatLaf took the click for a window drag). macOS — screen menu bar and `FULL_WINDOW_CONTENT`; `⌘Q` goes
  through `quit()` (QuitHandler).
- **A label with only `setPreferredSize` in a `BoxLayout` is 0×0 when it has no text** (BoxLayout also uses min/max) —
  set all three sizes.
- **Menu shortcut labels on Windows/Linux use English key names** ("Ctrl+Period") → `FerretMenuItemUI`.
- **Find in editor:** match colour — `setMarkAllHighlightColor`; on closing the panel — `clearMarkAllHighlights()`.
- **Sorting and search in results** — `TableRowSorter` + `RowFilter`, comparator by column type (`core/Values`).
  Re-apply comparators after `model.set`. Copy/CSV follow the visible order (`convertRowIndexToModel`).
- **A UI test that clicks a button opening a modal dialog must click via `SwingUtilities.invokeLater`** — called
  directly, the click waits until the dialog closes, and the test hangs on it.
- An exception in a test's `main` doesn't end the process (the UI thread is alive) → `System.exit` in `catch`.
- In WSL `pkill -f <text>` also kills your own shell if the text is in the command — use `pgrep -x java` and
  `/proc/<pid>/cmdline`.
