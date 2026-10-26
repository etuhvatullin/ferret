# Ferret — a SQL console for macOS and Windows

Ferret is a small, fast console for querying databases: Trino, ClickHouse, PostgreSQL, MS SQL, MySQL / MariaDB,
Oracle, Snowflake, BigQuery and a dozen more through JDBC. Type a query, press `Ctrl+Enter` (`⌘↵` on a Mac) and get a
table. Connections are **read-only by default**: a query that changes data (INSERT, UPDATE, DELETE, DROP…) is never
sent to the server.

No Java installation needed — it ships inside the app. Interface languages: English, Ukrainian, Russian, German and
Spanish (follows your system language; switch any time in **Settings**, `Ctrl+,` / `⌘,`).

## Install

Download the latest version from [Releases](../../releases/latest).

**Windows** — `Ferret-<version>-windows.zip`:

1. Extract the archive (right-click → "Extract All").
2. Double-click `Install Ferret.cmd`. No admin rights needed: Ferret goes to `%LOCALAPPDATA%\Programs\Ferret`, with
   shortcuts in the Start menu and on the desktop.
3. If Windows says "Windows protected your PC", click "More info" → "Run anyway". The app is not code-signed, so
   SmartScreen doesn't know it yet.

**macOS** — `Ferret-<version>-mac.dmg`:

1. Open the file and drag Ferret onto the "Applications" shortcut next to it.
2. On first launch macOS says it can't verify the developer. Open System Settings → Privacy & Security, find Ferret
   near the bottom and click "Open Anyway". You only need to do this once.

Run Ferret from "Applications": started from the open `.dmg`, it can't update itself.

## First run

1. The Connections window opens. Click "+", pick a database, fill in host, user and password.
2. Click "Test connection". On the first connection Ferret downloads the database driver from Maven Central and
   verifies its checksum — it takes a few seconds.
3. Click "Done". The table tree appears on the left; double-click a table to see its first 100 rows.

Passwords are stored only on your computer, in an encrypted file, and are never sent anywhere.

## Working with it

- `Ctrl+Enter` runs the query under the cursor. Queries are separated by `;` or an empty line. Select text to run just
  the selection. Inside a subquery Ferret asks whether to run the subquery or the whole query.
- A query without `LIMIT` returns the first 500 rows; "Show all" fetches the rest (up to 200,000).
- After `from` or `join` Ferret suggests tables: type the first letter, `Tab` inserts the suggestion, `Esc` opens the
  list manually.
- Results: click a column header to sort (again — reverse, a third time — original order); "Search in results" keeps
  rows containing the text. Select cells → `Ctrl+C` pastes as a table into Excel / Google Sheets; "Copy all" and
  "Save CSV…" take exactly what you see.
- Every tab is a query with its own server. Closed tabs are kept under "Consoles" on the left.

## Keyboard shortcuts

| Action | Windows | macOS |
|---|---|---|
| Run query | `Ctrl+Enter` | `⌘↵` |
| Stop query | `Ctrl+.` | `⌘.` |
| New tab / close tab | `Ctrl+T` / `Ctrl+W` | `⌘T` / `⌘W` |
| Reopen closed tab | `Ctrl+Shift+T` | `⇧⌘T` |
| Recent servers 1…9 | `Ctrl+1` … `Ctrl+9` | `⌃1` … `⌃9` |
| Settings | `Ctrl+,` | `⌘,` |
| Connections | `Ctrl+Shift+,` | `⇧⌘,` |
| Refresh table tree | `Ctrl+R` | `⌘R` |
| Find in query (in results — search in results) | `Ctrl+F`, then `Ctrl+G` | `⌘F`, then `⌘G` |
| Format query | `Ctrl+Alt+L` | `⌥⌘L` |
| Comment lines | `Ctrl+/` | `⌘/` |
| Indent / outdent | `Tab` / `Shift+Tab` (or `Ctrl+]` / `Ctrl+[`) | `Tab` / `⇧Tab` (or `⌘]` / `⌘[`) |

## Updates

On launch Ferret checks for a new version (for at most 3 seconds; offline it simply opens). If there is one, it
offers "Update" or "Later". "Update" downloads and verifies the archive, and Ferret restarts as the new version.
Check manually via **Help → Check for updates**; automatic checks can be turned off in **Settings**. Connections,
passwords and tabs are kept.

## Where your data lives

- Windows: `%APPDATA%\Ferret\v2`
- macOS: `~/Library/Application Support/Ferret/v2`

Connections, tabs, query history, encrypted passwords and the error log `ferret.log` — attach it when reporting a
problem (**Help → Report a problem** opens a pre-filled [issue](../../issues)).

## Building

Java 21 (`javac`, `jar`) is required. `./build.sh` — libraries, compilation, tests and `build/ferret.jar`;
`./package.sh win | mac` — the app with Java inside; `./release.sh "release notes"` — a GitHub release. Details are in
the script headers and in `CLAUDE.md`. Third-party components and their licenses — `THIRD-PARTY.md`.

## License

MIT — see `LICENSE`.
