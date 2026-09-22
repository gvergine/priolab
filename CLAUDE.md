# PrioLab

A JavaFX desktop application that helps the user prioritize *anything*. The
things to prioritize come from **connectors** — external programs PrioLab runs
with the project's settings in the environment, reading their result on
stdout. The prioritized result goes back out through **exporters**, the same
kind of program driven in the opposite direction.

## Tech stack

- **Java 21** (Gradle toolchain pins this; system JDK is 21 too).
- **Gradle** with **Groovy DSL** (`build.gradle`, not Kotlin). Use the wrapper
  (`./gradlew`) — the system-wide `gradle` is 4.4.1 and far too old.
- **JavaFX 21.0.4** via the `org.openjfx.javafxplugin`, UI defined in **FXML**.
- **Modular** app (`module-info.java`, module name `com.priolab`).
- **Jackson** (`jackson-databind`) for JSON config.
- **sqlite-jdbc** (xerial) for SQLite support.
- **badass-jlink** (`org.beryx.jlink`) for the runtime image + native installers
  (`jpackage`).

## Build & run

```bash
./gradlew run          # launch the app (needs a display)
./gradlew compileJava  # compile only
./gradlew jlink        # self-contained runtime image -> build/image, build/jpackage/priolab
./gradlew jpackage     # native installer for the current OS
./gradlew appimage     # portable, install-free Linux AppImage
```

Installer output:
- **Linux** (what we build here): `build/jpackage/priolab_<version>_amd64.deb`.
- **Windows**: `.msi` (per-user, with menu + shortcut). Must be built *on
  Windows* — `jpackage` only produces the host OS's installer format.

The installer bundles its own JRE, so end users don't need Java installed.

**Icons are per-OS**: `jpackage` only accepts the host's format, so the build
picks `packaging/windows/priolab.ico` on Windows and
`src/main/resources/com/priolab/img/priolab.png` elsewhere. Handing it the wrong
one is only a *warning* — and the bundle then silently ships the default Java
icon, which is what the Windows build did before the `.ico` existed. The `.ico`
is generated from the PNG (Pillow, BMP-encoded entries at 16/24/32/48/64/128/256
for maximum compatibility with the icon resource jpackage writes into the
launcher `.exe`); regenerate it if the app icon changes.

### AppImage (no installation)

`./gradlew appimage` wraps the very same jpackage **app image** (app + bundled
JRE) into a single file:
`build/distributions/PrioLab-<version>-x86_64.AppImage`. The user downloads it,
sets the executable bit (`chmod +x`, or *Properties ▸ Allow executing*) and
double-clicks it — nothing is installed and nothing but `~/.priolab` is written.

Three tasks make it up:
- `fetchAppImageTool` — downloads `appimagetool` to `build/tools/` (needs
  network; set `APPIMAGETOOL=/path/to/appimagetool` to use a local copy
  instead). `appimagetool` in turn fetches the AppImage *runtime* it embeds.
- `appDir` — assembles `build/AppDir`: the jpackage image under `usr/`, plus the
  `AppRun` launcher and `priolab.desktop` from `packaging/appimage/`, and the
  app icon `src/main/resources/com/priolab/img/priolab.png` — one PNG serves the
  JavaFX stage, `jpackage --icon` and the AppImage. It wipes
  the AppDir first, because the jlink runtime's `legal/` files are read-only and
  cannot be copied over in place.
- `appimage` — runs `appimagetool` over the AppDir. It is invoked with
  `--appimage-extract-and-run` so the *build* works without FUSE; the resulting
  AppImage still uses FUSE at run time on the user's machine, and falls back to
  `./PrioLab-….AppImage --appimage-extract-and-run` where FUSE is missing.

`appimage` is deliberately not wired into `build`/`assemble` — it needs network
access on the first run.

## Runtime behavior

### First run / configuration
On startup PrioLab looks for `~/.priolab/config.json`:
- **Missing** → treated as a fresh install. The **wizard** (`wizard.fxml` /
  `WizardController`) asks for the **connectors** and **exporters** directories
  and writes `config.json`.
- **Present but without `exportersDir`** (a config written before exporters
  existed) → the wizard runs again, prefilled with what is already configured,
  so the missing directory can be filled in.
- **Complete** → loaded, then the **main window** (`main.fxml` /
  `MainController`) opens.

`config.json` schema (see `config/Config.java`):
```json
{
  "version": 1,
  "connectorsDir": "/home/user/.priolab/connectors",
  "exportersDir": "/home/user/.priolab/exporters"
}
```

### Connectors & exporters
A connector (the **importer**) and an exporter are the *same kind of thing* —
`model/PluginKind.java` names the two roles, and one `connector/Connector.java`
implements both. Each is a **direct subdirectory** of its own configured
directory (`connectorsDir` / `exportersDir`); the **directory name is the
program's name**. A subdirectory is a valid connector
only if it contains a **`manifest.json`** (`connector/ConnectorManifest.java`,
Jackson-mapped) with:
- `name` (string, **must equal the directory name**),
- `version` (string), `author` (string), `description` (string),
- `command` (string) — the command PrioLab runs, **relative to the connector
  directory**,
- `keys` (array of strings, defaults to empty) — the setting names the user
  configures per project; their values are meant to live in a table of the
  project's SQLite file.

`ConnectorManager.discover()` scans the direct subdirectories, parses/validates
each `manifest.json`, and **skips invalid ones** (writing a note to stderr:
missing manifest, name mismatch, or missing version/author/description/command).
`MainController` keeps **one manager per kind**, each over its own directory.

**Running:** `Connector.run(env, onLine, onErrorLine, onExit)` launches the
manifest `command` as a child process with the connector directory as the
working directory. A relative program token (contains `/`, e.g. `./run.sh`) is
resolved against the connector directory since Java resolves relative
executables against the JVM's cwd, not `ProcessBuilder.directory`.

**There is no handshake and nothing is ever written to the connector's stdin**
(it is closed right after launch, so a connector that reads it sees EOF).
Everything PrioLab has to say is in the environment; everything the connector
has to say is on stdout.

**stdout and stderr are read separately**, each line by line on its own daemon
thread: **stdout is the result** PrioLab parses, **stderr is diagnostics** that
only reach the console pane. A connector may therefore log freely to stderr
without corrupting its output. The exit callback fires after stdout ends (and
after a short join on the stderr reader, so trailing diagnostics still land
before `[exited with code N]`).

**Run protocol.** `MainController.onRunConnector()` runs the current document's
selected connector:

1. PrioLab launches `command` with **one environment variable per manifest
   `key`, named exactly like the key**, holding the value configured for this
   project (empty string when the user has not set one). These are *added* to
   the environment PrioLab itself was started with, so `PATH` & co. are
   inherited. A key named like an existing variable overrides it.
2. The connector prints a **count** line (an integer N) as its first non-blank
   stdout line, followed by **N** lines, each a **JSON object** with keys `id`,
   `description`, `url` (parsed with Jackson into `model/PrioItem.java`; blank
   lines are skipped, malformed objects are logged and skipped). Anything it
   prints after that is only logged.

The parse is a small state machine in `MainController` (`RunPhase`:
`AWAIT_COUNT → READ_ITEMS → DONE`), fed by `handleConnectorLine`. The resulting
items are handed to `DocumentController.setItems(...)`. While a connector runs,
`setRunning(...)` locks the window down (see *Documents & editing*); the exit
callback releases it. `shutdown()` kills the process on exit/close.

The main window's **Console pane** (bottom of a vertical `SplitPane`, with a
**Clear** button) shows the run: the command line, the environment PrioLab
passed, then every stdout and stderr line, appended via `Platform.runLater`.

A minimal connector is therefore just:

```python
#!/usr/bin/env python3
import json, os
items = [{"id": "A-1", "description": "something", "url": os.getenv("baseurl", "")}]
print(len(items), flush=True)
for item in items:
    print(json.dumps(item), flush=True)
```

**Export protocol.** `MainController.onExport()` is the mirror image:

1. The exporter's command is launched exactly like a connector's — same
   directory rules, same **environment variables** from its own manifest keys
   and its own per-project settings.
2. PrioLab then writes the items **currently shown**, in **priority order**
   (the result table's order: WSJF descending, unscored last), **one JSON
   object per line on the exporter's stdin**, and **closes stdin** so the
   program sees end-of-file. There is no count line — the line order *is* the
   priority.
3. Its stdout and stderr are only logged to the console pane; PrioLab parses
   nothing back.

Each exported line carries the item as the connector delivered it plus the four
WSJF inputs and the computed value (`null` wherever a dropdown is blank, and
`wsjf` rounded to the two decimals the table shows):

```json
{"id":"A-1","description":"…","url":"…","businessValue":8,"timeCriticality":5,
 "riskReduction":3,"jobSize":3,"wsjf":5.33}
```

Writing happens on its own daemon thread, so an exporter that reads only part of
its input (or none) cannot block PrioLab; a broken pipe is reported in the log,
not raised. **Export** is a no-op with a status message when nothing has been
imported yet.

A minimal exporter is therefore just:

```python
#!/usr/bin/env python3
import json, os, sys
with open(os.getenv("outfile"), "w") as out:
    for line in sys.stdin:
        item = json.loads(line)
        out.write(f"{item['id']},{item['wsjf']}\n")
```

### SQLite
`File ▸ Open…` / `File ▸ Save…` in the main window open/create a `.sqlite3`
(also `.sqlite`, `.db`) file via `db/Database.java` (JDBC, `jdbc:sqlite:`). The
driver is loaded through the JDBC `ServiceLoader`, so no explicit
`requires org.xerial.sqlitejdbc` in `module-info` — only `requires java.sql`.
`Database` also exposes a small `meta(key, value)` key/value table
(`ensureSchema`, `getMeta`, `putMeta`) used to store project metadata, plus
`connector_settings(connector, key, value)`,
`exporter_settings(exporter, key, value)` and `item_scores(id, business_value,
time_criticality, risk_reduction, job_size)`. The two settings tables are
reached through one `PluginKind`-parameterized trio
(`getAllSettings` / `putSetting` / `deleteSetting`), so a connector and an
exporter that share a name keep separate settings. `ensureSchema()` is
`CREATE TABLE IF NOT EXISTS` throughout, so opening an older project file just
adds the missing tables.

### New Project
`File ▸ New Project…` opens a small **modal** wizard (`newproject.fxml` /
`NewProjectController`) that requires a **name** and a **file location**. On
finish, `Database.createProject(path, name)` opens/creates the SQLite file and
initialises the `meta` table with `name` and `schema_version`
(`Database.SCHEMA_VERSION`). Unlike the first-run wizard (a scene swap), this
one is a separate `Stage` shown with `showAndWait()`; the controller exposes the
chosen name+path via `getResult()` (null = cancelled).

### Documents & editing
An open project is a `doc/Document.java`: it owns the `Database`, holds the
editable in-memory state, and tracks a JavaFX `dirty` property (in-memory value
vs. what's on disk). `Document.create(file, name)` / `Document.open(file)` are
the factories; `save()` writes back and clears dirty.

`MainController` owns the current `Document` and hosts the editor view
(`document.fxml` / `DocumentController`) inside the center `contentPane`.

**Connector and exporter selection & settings live in the top bar** (an `HBox`
alongside the `MenuBar`), owned by `MainController`, not the document view. It
holds **two identical groups**, connector first, exporter after a separator:
- a `ComboBox` of that kind's discovered names, two-way bound to
  `Document.selectedProperty(kind)` and persisted to `meta["connector"]` /
  `meta["exporter"]`;
- a **gear settings button** (`img/gear.png` as the button's `graphic` — a glyph
  like `⚙` was invisible on Linux and tiny on Windows; its `ImageView` is sized
  from the button's font, so it follows the zoom) that opens a modal dialog
  (`pluginsettings.fxml` /
  `PluginSettingsController`, shared by both kinds) with one text field per
  manifest **key**, seeded from and (on OK) written back into the document.
  These key values are persisted per kind (see *SQLite*) and tracked by
  `Document` (`getSetting` / `setSetting`, folded into the `dirty` flag). They
  are the **environment variables** the command is launched with;
- the action button — **Import** for the connector, **Export** for the exporter
  — and its own progress spinner.

Both bars are disabled until a project is open.

**A run owns the window.** `setRunning(kind, busy)` disables the **menu bar,
both program groups and the item tables** until the process exits — nothing can
be re-run, re-selected, reconfigured or edited underneath a running connector or
exporter — and shows only that kind's spinner. The console pane stays live
(including **Clear**) so the output can be watched, and the window's close
button still works: `shutdown()` kills the child process.

**Zoom.** The main window scales with **Ctrl+scroll** anywhere in it (also
Ctrl+plus / Ctrl+minus, and Ctrl+0 back to 100%), clamped to 60%–250% in 10%
steps, with the level echoed in the status bar. `MainController.installZoom()`
implements it by setting `-fx-font-size` on the window's root
(`BASE_FONT_SIZE = 13px` = 100%, matching `.root` in app.css): JavaFX's control
styling is font-relative, so text, paddings and table rows all grow and the
layout **reflows**. `Node.setScaleX/Y` would instead magnify a fixed layout and
push half the window out of view, which is why it is not used. The handlers are
event *filters* on the root, so Ctrl+scroll over a table or the console zooms
instead of scrolling it. The zoom is per-session — it is not persisted — and
only applies to the main window, not the modal dialogs.

**Center view** (`DocumentController`) is a **horizontal `SplitPane`** of two
`TableView`s over `model/ScoredItem.java` (a `PrioItem` plus editable WSJF
scoring state). Each row's `id` is a `Hyperlink` that opens the item's `url` via
`HostServices.showDocument`.

- **Left table** has: **Order** (1-based, the sequence in which the connector
  emitted the item — stored on `ScoredItem`), **ID**, **Description**, the four
  WSJF inputs — **UBV**, **TC**, **RR/RO**, **Size** — each an in-cell `ComboBox`
  of *blank* (default, = `null`) or a modified-Fibonacci number
  (`1, 2, 3, 5, 8, 13, 20, 40, 100`), and a computed **WSJF** column. **Every
  column sorts**, ascending and descending, by clicking its header — including
  each WSJF input and the WSJF result. For the nullable columns a blank counts
  as the *lowest* value (`blankLowest()`), so descending — the interesting
  direction — leaves the unscored items last, like the result table; **Order**
  always takes you back to the connector's own sequence. Editing a score does
  **not** re-run the sort (rows would jump under the cursor mid-edit), but a new
  import does, so the rows never contradict the header's sort arrow.
  Rows that are not fully scored (WSJF still blank) are highlighted **light
  yellow** via the `:incomplete` pseudo-class (see `app.css`).
- **WSJF** = `(BusinessValue + TimeCriticality + RiskReduction) / JobSize`,
  formatted to two decimals; **blank while any of the four inputs is Undefined**.
  It is a `Bindings.createObjectBinding` on `ScoredItem` (nullable `Double`) that
  recomputes as inputs change.
- **Right table** (unsortable) has **Priority**, **WSJF**, **ID**, **Description**.
  It shows the same `ScoredItem` instances ordered by **WSJF descending**
  (Undefined last); **Priority** is the 1-based row position. `DocumentController`
  listens to each item's `wsjfProperty()` and re-sorts the right table's backing
  list (`FXCollections.sort` + `refresh()`) on every change, so editing a score
  on the left instantly reorders the result on the right.

**Score persistence.** The four dropdown values are stored per item **id** in the
`item_scores` table — only the id and the four numbers, nothing else about the
item. `Document` holds them as `id -> ItemScore` (`model/ItemScore.java`, a record
of four nullable `Integer`s) in the same saved/edit pair used for connector
settings, so:

- editing any dropdown calls `Document.setItemScore(...)`, which **marks the
  document dirty** (title gets a `*`, and the Yes/No/Cancel prompt guards
  New/Open/Exit/close);
- a score with all four values blank is normalised away and its row is deleted
  on save;
- `Document` keeps **every** stored score, not just the items of the last run, so
  scores for items a run did not return are preserved and reappear when those
  items come back.

**A connector run shows exactly what the connector returned** — stored scores
never add rows. `DocumentController.setItems(...)` seeds each item from
`Document.getItemScore(id)` *before* attaching the write-back listeners, so
restoring a score is not itself an edit and does not dirty the document; ids with
no stored score simply start blank.

Unsaved-changes handling: `New Project`, `Open`, `Exit`, and the window's close
button all route through `MainController.maybeSaveCurrent()` (Yes/No/Cancel);
the window title shows `PrioLab — <name>` with a trailing `*` while dirty. The
close button is wired via `stage.setOnCloseRequest(controller::handleCloseRequest)`
in `App.showMain()`.

## Project layout

```
build.gradle                       Groovy build, JavaFX + jlink/jpackage/appimage config
settings.gradle
packaging/appimage/                AppImage assets
  AppRun                           entry point -> usr/bin/priolab
  priolab.desktop                  desktop entry (also used for the menu entry)
packaging/windows/priolab.ico      app icon in Windows' own format (jpackage)
src/main/java/module-info.java     module com.priolab
src/main/java/com/priolab/
  App.java                         Application entry; chooses wizard vs main
  config/Config.java               config.json POJO
  config/ConfigManager.java        load/save ~/.priolab/config.json (Jackson)
  connector/Connector.java         one connector/exporter dir: manifest + child process
  connector/ConnectorManifest.java manifest.json POJO (name/version/author/…/keys)
  connector/ConnectorManager.java  discover connector subdirs in the config'd dir
  controller/WizardController.java first-run wizard
  controller/NewProjectController.java  modal "new project" wizard
  controller/PluginSettingsController.java  modal key editor (connector or exporter)
  controller/MainController.java   main window, top bar, import/export runs, current Document
  controller/DocumentController.java    center split view: WSJF scoring + result tables
  doc/Document.java                open project: DB + editable state + dirty
  db/Database.java                 SQLite JDBC wrapper + meta / per-kind settings / scores
  model/PrioItem.java              one item to prioritize (id/description/url)
  model/ScoredItem.java            PrioItem + WSJF inputs + computed WSJF
  model/ItemScore.java             the four stored WSJF inputs, keyed by item id
  model/PluginKind.java            CONNECTOR (importer) vs EXPORTER
src/main/resources/com/priolab/
  img/priolab.png                  256x256 app icon (stage, jpackage, AppImage)
  img/gear.png                     settings-button icon
  fxml/wizard.fxml
  fxml/newproject.fxml
  fxml/pluginsettings.fxml
  fxml/main.fxml
  fxml/document.fxml
  css/app.css
```

## Module system notes (important)

- `module-info.java` `opens` the controller package to `javafx.fxml` (FXML
  reflection) and the config package to `com.fasterxml.jackson.databind`.
- `sqlite-jdbc` is a non-modular (automatic) jar. The jlink config
  `forceMerge('sqlite-jdbc')` folds it into the merged module; `mergedModule`
  declares its `requires java.sql` / `requires java.naming`. If you add another
  non-modular dependency and jlink fails, extend `forceMerge` / `mergedModule`
  similarly.
- `mergedModule` must also declare
  `provides 'java.sql.Driver' with 'org.sqlite.JDBC'`. `ServiceLoader` ignores
  `META-INF/services` inside a *named* module, so without that clause the merged
  module is never service-bound into the image's module graph and every
  `DriverManager.getConnection("jdbc:sqlite:…")` in the jlink/jpackage/AppImage
  build fails with *"No suitable driver found"* — while `./gradlew run`, which
  runs off the classpath-ish dev module path, works fine. Any future
  service-provider dependency needs the same treatment.

## Conventions

- Keep the app modular — new packages that FXML or Jackson touch by reflection need
  matching `opens` in `module-info.java`.
- The connector contract is *environment variables in, stdout out*; the exporter
  contract is *environment variables in, JSON lines down stdin*. Keep them that
  way: no handshake, nothing parsed out of stderr, nothing read back from an
  exporter.
- Connectors and exporters stay interchangeable in everything but direction —
  same manifest, same discovery, same settings dialog. Anything new that applies
  to one should be expressed per `PluginKind` rather than duplicated.
- Sizes in `app.css` are **em**, not px, so Ctrl+scroll zoom scales them; only
  `.root`'s own 13px baseline and a few hairline paddings stay absolute. A new
  `-fx-font-size` in px would simply ignore the zoom. Table rows need
  `-fx-cell-size` in em for the same reason.
- Pin dependency versions in the `ext { }` block in `build.gradle`.
