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
A connector is an executable in the connectors directory. PrioLab launches it as
a child process and speaks a **line-delimited protocol**: one request line to
the connector's stdin, one response line back on its stdout. stderr is kept
separate (inherited) for diagnostics.

Command vocabulary lives in `connector/Protocol.java`:
`initialize`, `list_tasks`, `save`, `shutdown`. Payloads are JSON, e.g.
request `{"command":"list_tasks","args":{...}}`, response
`{"ok":true,"data":{...}}`. `Connector` handles process lifecycle + framing
only; it does not interpret payloads. `ConnectorManager.discover()` lists
executable files in the connectors directory (not started until first `send`).

### SQLite
`File ▸ Open…` / `File ▸ Save…` in the main window open/create a `.sqlite3`
(also `.sqlite`, `.db`) file via `db/Database.java` (JDBC, `jdbc:sqlite:`). The
driver is loaded through the JDBC `ServiceLoader`, so no explicit
`requires org.xerial.sqlitejdbc` in `module-info` — only `requires java.sql`.
**The main content area is intentionally empty for now**; wiring the DB and
connectors into a real prioritization UI is the next step.

## Project layout

```
build.gradle                       Groovy build, JavaFX + jlink/jpackage config
settings.gradle
src/main/java/module-info.java     module com.priolab
src/main/java/com/priolab/
  App.java                         Application entry; chooses wizard vs main
  config/Config.java               config.json POJO
  config/ConfigManager.java        load/save ~/.priolab/config.json (Jackson)
  connector/Connector.java         one child process, line protocol
  connector/ConnectorManager.java  discover connectors in the config'd dir
  connector/Protocol.java          command name constants
  controller/WizardController.java first-run wizard
  controller/MainController.java   main window, File menu (SQLite open/save)
  db/Database.java                 SQLite JDBC connection wrapper
src/main/resources/com/priolab/
  fxml/wizard.fxml
  fxml/main.fxml
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
