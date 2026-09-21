# PrioLab

A JavaFX desktop application that helps the user prioritize *anything*. The
things to prioritize come from **connectors** — external programs PrioLab drives
over stdin/stdout.

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
```

Installer output:
- **Linux** (what we build here): `build/jpackage/priolab_<version>_amd64.deb`.
- **Windows**: `.msi` (per-user, with menu + shortcut). Must be built *on
  Windows* — `jpackage` only produces the host OS's installer format.

The installer bundles its own JRE, so end users don't need Java installed.

## Runtime behavior

### First run / configuration
On startup PrioLab looks for `~/.priolab/config.json`:
- **Missing** → treated as a fresh install. The **wizard** (`wizard.fxml` /
  `WizardController`) asks for the **connectors directory** and writes
  `config.json`.
- **Present** → loaded, then the **main window** (`main.fxml` /
  `MainController`) opens.

`config.json` schema (see `config/Config.java`):
```json
{ "version": 1, "connectorsDir": "/home/user/.priolab/connectors" }
```

### Connectors
A connector is a **direct subdirectory** of the connectors directory; the
**directory name is the connector name**. A subdirectory is a valid connector
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

**Running:** `Connector.run(onLine, onExit)` launches the manifest `command` as a
child process with the connector directory as the working directory,
**merges stderr into stdout** (`redirectErrorStream(true)`) and streams the
output line by line on a daemon thread. A relative program token (contains `/`,
e.g. `./run.sh`) is resolved against the connector directory since Java resolves
relative executables against the JVM's cwd, not `ProcessBuilder.directory`.

The main window's **Console pane** (bottom of a vertical `SplitPane`, with a
**Clear** button) shows the raw output; every line the connector prints is
appended via `Platform.runLater`.

**Run protocol.** `MainController.onRunConnector()` runs the current document's
selected connector and drives a small line-oriented handshake over the child's
stdin/stdout (constants in `connector/Protocol.java`; `Connector.send(line)`
writes a line to stdin):

1. PrioLab writes `INIT key=value key=value…` (one line) — the per-connector
   setting values from the document (see *Documents & editing*).
2. The connector's **next line must be `OK`**. If it is anything else, PrioLab
   does nothing further — the connector's error text is already in the log.
3. On `OK`, PrioLab writes `GET`.
4. The connector then prints a **count** line (an integer N), followed by **N**
   lines, each a **JSON object** with keys `id`, `description`, `url` (parsed
   with Jackson into `model/PrioItem.java`; blank lines are skipped, malformed
   objects are logged and skipped).

The parse is a small state machine in `MainController` (`RunPhase`:
`AWAIT_ACK → AWAIT_COUNT → READ_ITEMS → DONE`), fed by `handleConnectorLine`.
The resulting items are handed to `DocumentController.setItems(...)`. While a
connector runs, `setRunning(true)` disables the connector inputs (combo, ⚙
settings, Run) and shows an indeterminate `ProgressIndicator`; the exit callback
re-enables them. `shutdown()` kills the process on exit/close.

`connector/Protocol.java` also still defines a JSON command vocabulary
(`initialize`, `list_tasks`, `save`, `shutdown`) reserved for future structured
request/response comms.

### SQLite
`File ▸ Open…` / `File ▸ Save…` in the main window open/create a `.sqlite3`
(also `.sqlite`, `.db`) file via `db/Database.java` (JDBC, `jdbc:sqlite:`). The
driver is loaded through the JDBC `ServiceLoader`, so no explicit
`requires org.xerial.sqlitejdbc` in `module-info` — only `requires java.sql`.
`Database` also exposes a small `meta(key, value)` key/value table
(`ensureSchema`, `getMeta`, `putMeta`) used to store project metadata, plus the
`connector_settings(connector, key, value)` and `item_scores(id, business_value,
time_criticality, risk_reduction, job_size)` tables. `ensureSchema()` is
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

**Connector selection & settings live in the top bar** (an `HBox` alongside the
`MenuBar`), owned by `MainController`, not the document view:
- a `ComboBox` of the currently loaded connector names
  (`ConnectorManager.discover()`), two-way bound to
  `Document.selectedConnectorProperty()` and persisted to `meta["connector"]`;
- a **⚙ settings button** that opens a modal dialog (`connectorsettings.fxml` /
  `ConnectorSettingsController`) with one text field per manifest **key**, seeded
  from and (on OK) written back into the document. These per-connector key values
  are persisted in the `connector_settings(connector, key, value)` table and
  tracked by `Document` (`getConnectorSetting` / `setConnectorSetting`, folded
  into the `dirty` flag). They are what `INIT key=value …` sends at run time;
- the **Run** button and its progress spinner.

The whole connector bar is disabled until a project is open.

**Center view** (`DocumentController`) is a **horizontal `SplitPane`** of two
`TableView`s over `model/ScoredItem.java` (a `PrioItem` plus editable WSJF
scoring state). Each row's `id` is a `Hyperlink` that opens the item's `url` via
`HostServices.showDocument`.

- **Left table** has: **Order** (1-based, the sequence in which the connector
  emitted the item — stored on `ScoredItem`), **ID**, **Description**, the four
  WSJF inputs — **UBV**, **TC**, **RR/RO**, **Size** — each an in-cell `ComboBox`
  of *blank* (default, = `null`) or a modified-Fibonacci number
  (`1, 2, 3, 5, 8, 13, 20, 40, 100`), and a computed **WSJF** column. Only
  **Order** and **ID** (the "key") are sortable; the other columns are fixed.
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
build.gradle                       Groovy build, JavaFX + jlink/jpackage config
settings.gradle
src/main/java/module-info.java     module com.priolab
src/main/java/com/priolab/
  App.java                         Application entry; chooses wizard vs main
  config/Config.java               config.json POJO
  config/ConfigManager.java        load/save ~/.priolab/config.json (Jackson)
  connector/Connector.java         one connector dir: manifest + child process
  connector/ConnectorManifest.java manifest.json POJO (name/version/author/…/keys)
  connector/ConnectorManager.java  discover connector subdirs in the config'd dir
  connector/Protocol.java          command/token constants (INIT/OK/GET + JSON vocab)
  controller/WizardController.java first-run wizard
  controller/NewProjectController.java  modal "new project" wizard
  controller/ConnectorSettingsController.java  modal connector-key editor
  controller/MainController.java   main window, top bar, run protocol, current Document
  controller/DocumentController.java    center split view: WSJF scoring + result tables
  doc/Document.java                open project: DB + editable state + dirty
  db/Database.java                 SQLite JDBC wrapper + meta / connector_settings
  model/PrioItem.java              one item to prioritize (id/description/url)
  model/ScoredItem.java            PrioItem + WSJF inputs + computed WSJF
  model/ItemScore.java             the four stored WSJF inputs, keyed by item id
src/main/resources/com/priolab/
  fxml/wizard.fxml
  fxml/newproject.fxml
  fxml/connectorsettings.fxml
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

## Conventions

- Keep the app modular — new packages that FXML or Jackson touch by reflection need
  matching `opens` in `module-info.java`.
- New connector commands: add a constant to `Protocol.java` first.
- Pin dependency versions in the `ext { }` block in `build.gradle`.
