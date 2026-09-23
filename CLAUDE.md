# PrioLab

A JavaFX desktop application that helps the user prioritize *anything*. The
things to prioritize come from **connectors** — external programs PrioLab runs
with the project's settings in the environment. A connector goes **both ways**:
asked to *load* it prints the items as one JSON array on stdout, asked to
*save* it reads that same array back on stdin. **Exporters** are the same kind
of program wired one way only: they turn that array into a file — a
spreadsheet, a PDF — and give nothing back.

PrioLab stores no items of its own: **the connector is the storage**, which is
why its two buttons say *Load* and *Save* while the exporter's says *Export*.

## Tech stack

- **Java 21** (Gradle toolchain pins this; system JDK is 21 too).
- **Gradle** with **Groovy DSL** (`build.gradle`, not Kotlin). Use the wrapper
  (`./gradlew`) — the system-wide `gradle` is 4.4.1 and far too old.
- **JavaFX 21.0.4** via the `org.openjfx.javafxplugin`, UI defined in **FXML**.
- **Modular** app (`module-info.java`, module name `com.priolab`).
- **Jackson** (`jackson-databind`) for all JSON: the config, the project files
  and the item payloads exchanged with connectors and exporters.
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

**The version lives in exactly one place**: the `version` property in
`build.gradle`. `compileJava` records it in the module descriptor
(`options.javaModuleVersion`), jlink and jpackage carry that into the runtime
image, and `App.version()` reads it back — `App.class.getModule()
.getDescriptor().rawVersion()`, falling back to `"dev"` outside a versioned
module — for the **About** dialog. The same property names the AppImage, the
portable zips and the CI artifacts, so bumping it there is the whole release
edit.

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

### CI (`.github/workflows/build.yml`)

Pushes, PRs and `v*` tags build the two things a user can download and run, and
nothing else — **no installers**:

| Job | Runner | Artifacts |
|---|---|---|
| `linux` | `ubuntu-22.04` | `PrioLab-<version>-x86_64.AppImage`, `PrioLab-<version>-linux-x86_64.zip` |
| `windows` | `windows-latest` | `PrioLab-<version>-windows-x64.zip` |
| `release` | on `v*` tags | the above + `SHA256SUMS`, attached to a GitHub Release via `gh` |

Both zips are the **jpackage app image** (app + bundled JRE + the native
launcher), so they need no Java and no install: unzip and run `bin/priolab` /
`priolab.exe`. The Linux job runs `./gradlew appimage`, which already depends on
`jpackageImage`, so one build yields both artifacts.

Two runner choices worth keeping:
- **`ubuntu-22.04`, not `ubuntu-latest`**: binaries linked against a newer glibc
  refuse to start on older distributions, so build on the oldest supported
  runner.
- **No WiX on Windows**: `jpackage` needs it for `.msi`/`.exe` *installers*, not
  for an app image, so the Windows job needs no extra tooling.

The AppImage step needs `squashfs-tools` (appimagetool shells out to
`mksquashfs`) but no FUSE, since the build passes
`--appimage-extract-and-run`.

Artifact names come from the Gradle `version`, while the release is named after
the **tag** — tagging `v1.1.0` without bumping `version` in `build.gradle` would
publish a `v1.1.0` release full of `1.0.0` files. Bump first, then tag.

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
  "exportersDir": "/home/user/.priolab/exporters",
  "lastProjectFile": "/home/user/projects/roadmap.json"
}
```

`lastProjectFile` is written whenever a project is opened or created and read
back at startup, so PrioLab reopens the project you were last in. A missing or
deleted file is simply ignored and the app starts with no project.

### Connectors & exporters
A connector (which goes **both ways**) and an exporter (**out only**) are the
*same kind of thing* — `model/PluginKind.java` names the two roles, and one
`connector/Connector.java` runs both. Each is a **direct subdirectory** of its own configured
directory (`connectorsDir` / `exportersDir`); the **directory name is the
program's name**. A subdirectory is a valid connector
only if it contains a **`manifest.json`** (`connector/ConnectorManifest.java`,
Jackson-mapped) with:
- `name` (string, **must equal the directory name**),
- `version` (string), `author` (string), `description` (string),
- `command` (string) — the command PrioLab runs, **relative to the connector
  directory**,
- `keys` (array of strings, defaults to empty) — the setting names the user
  configures per project; their values live in the project file and are passed
  to the command as environment variables.

`ConnectorManager.discover()` scans the direct subdirectories, parses/validates
each `manifest.json`, and **skips invalid ones** (writing a note to stderr:
missing manifest, name mismatch, or missing version/author/description/command).
`MainController` keeps **one manager per kind**, each over its own directory.

**Running:** `Connector.run(env, stdin, onLine, onErrorLine, onExit)` launches
the manifest `command` as a child process with the connector directory as the
working directory. A relative program token (contains `/`, e.g. `./run.sh`) is
resolved against the connector directory since Java resolves relative
executables against the JVM's cwd, not `ProcessBuilder.directory`.

**There is no handshake.** `stdin` is either the whole JSON payload — written
on its own daemon thread, then closed, so a program that reads only part of it
cannot block PrioLab — or `null`, which closes stdin immediately so an
importing connector sees EOF instead of hanging.

**stdout and stderr are read separately**, each line by line on its own daemon
thread: **stdout is the result** PrioLab parses, **stderr is diagnostics** that
only reach the console pane. A connector may therefore log freely to stderr
without corrupting its output. The exit callback fires after stdout ends (and
after a short join on the stderr reader, so trailing diagnostics still land
before `[exited with code N]`).

**Run protocol.** A connector is asked to do one of two things, and which one
is in the environment:

1. PrioLab launches `command` with **one environment variable per manifest
   `key`**, named exactly like the key, holding the value configured for this
   project (empty string when unset), **plus `operation`** — `load` or `save`
   (`Connector.ENV_OPERATION` / `OPERATION_LOAD` / `OPERATION_SAVE`).
   These are *added* to the environment PrioLab itself was started with, so
   `PATH` & co. are inherited. A key named like an existing variable overrides it.
2. **Load** (`operation=load`): the connector prints **one JSON array of
   items** on stdout. PrioLab collects the whole of stdout and parses it when
   the process exits — the array may be pretty-printed across as many lines as
   the connector likes — then replaces the items with it. A non-zero exit code
   leaves the current items alone.
3. **Save** (`operation=save`): PrioLab writes that same array to the
   connector's stdin and closes it. A **zero exit code means the connector has
   taken the items**, which is what clears the dirty flag.

An **exporter** is the save half wired to a different purpose: same launch,
same array on stdin, **no `operation` variable** (it only ever does the one
thing), and its exit code changes nothing in the document — a spreadsheet on
disk says nothing about whether the connector still holds the items.

**The item shape** is the whole contract, and it is the same in both
directions:

```json
{"id": "Apple", "description": "Test task 1", "url": "http://…/Apple",
 "UBV": "1", "TC": "2", "RROE": "3", "JS": "5"}
```

`id`, `description` and `url` are strings. The four score keys — **UBV**, **TC**,
**RROE**, **JS** — are **mandatory** and hold either `null` or a *string* with
the number. PrioLab parses them into nullable integers and writes them back out
as strings, so a connector that copies them straight into its own storage gets
back what it gave. A missing key, or one holding something that is not a number,
is logged in the console and treated as blank rather than failing the load.
Items go out in **priority order** (WSJF descending, unscored last); nothing
else about the order is meaningful, since a connector matches items by `id`.

The main window's **Console pane** (bottom of a vertical `SplitPane`, with a
**Clear** button) shows the run: the command line, the environment PrioLab
passed, then every stdout and stderr line, appended via `Platform.runLater`.

`examples/connectors/filebased` is a complete connector in ~40 lines of Python:
it keeps its own `db.json`, serves it on `operation=load` and writes the four
scores back into it on `operation=save`.

### The project file

`File ▸ New Project…` and `File ▸ Open…` create or open a **`.json` project
file** (`doc/Project.java`, Jackson-mapped, `FileChooser` in both cases — there
is no separate dialog). **There is no Save.** The file holds only this:

```json
{
  "version": 1,
  "connector": "filebased",
  "exporter": null,
  "connectorSettings": { "filebased": { "file": "/home/me/db.json" } },
  "exporterSettings": {}
}
```

— which connector and exporter the project uses and the values given to their
manifest keys. **The items are not in it**: the connector loads them and takes
them back on a save, so the project file only remembers how to reach them.

It is written **silently**: when the document is replaced and when the app exits
(`MainController.shutdown()` → `saveProjectQuietly()`), never through a menu
item, and editing any of it never marks the document dirty.

### Documents & editing
An open project is a `doc/Document.java`: the `Project` file plus the items
being prioritized. `Document.create(file)` / `Document.open(file)` are the
factories, and `save()` writes the project file (silently — see above).

**What `dirty` means here.** The project file is never at risk: it is saved on
its own. The *items* are, because they only exist in memory until a connector
takes them, so `dirty` tracks exactly that:

- **loading** replaces the items and leaves the document **dirty only if the
  load changed items that were already there**. Loading into an empty document,
  or a load that returns exactly what is shown, leaves it clean;
- **editing a score** dirties it, and editing it back does not;
- a **successful connector save** (exit code 0) cleans it — the connector now
  holds what is on screen;
- the **exporter** never touches it, and neither does choosing a connector, an
  exporter or editing their settings.

`Document` keeps a `synced` snapshot of the items as the connector last gave or
took them, plus an `importOverwrote` flag for the rule above;
`recomputeDirty()` is `importOverwrote || !snapshot().equals(synced)`.

Because the items cannot be saved anywhere except through a connector, **New /
Open / Exit warn and offer to continue or stay** (`confirmDiscardItems()`), and
a **load over existing items asks first** (`confirmReplaceItems()`) — it is the
one action that silently throws work away.

`MainController` owns the current `Document` and hosts the editor view
(`document.fxml` / `DocumentController`) inside the center `contentPane`.

**Connector and exporter selection & settings live in the top bar** (an `HBox`
alongside the `MenuBar`), owned by `MainController`, not the document view. It
holds **two identical groups**, connector first, exporter after a separator:
- a `ComboBox` of that kind's discovered names, two-way bound to
  `Document.selectedProperty(kind)` and written into the project file's
  `connector` / `exporter` field;
- a **gear settings button** (`img/gear.png` as the button's `graphic` — a glyph
  like `⚙` was invisible on Linux and tiny on Windows; its `ImageView` is sized
  from the button's font, so it follows the zoom) that opens a modal dialog
  (`pluginsettings.fxml` /
  `PluginSettingsController`, shared by both kinds) with one text field per
  manifest **key**, seeded from and (on OK) written back into the document.
  These key values live in the project file per kind
  (`Document.getSetting` / `setSetting`) and never touch the `dirty` flag. They
  are the **environment variables** the command is launched with;
- the action buttons. The **connector** has two, because it is the storage:
  **Load** (`img/load.png`, arrow into a tray) runs it with `operation=load`,
  **Save** (`img/save.png`, arrow out of a tray) with `operation=save`. The
  **exporter** has one, **Export**, carrying a *page* icon
  (`img/document.png`) rather than an arrow — a different shape, not just a
  different direction, so the two are not confused at a glance. All three have
  tooltips spelling out what they run, and their icons are sized from the
  button font like the gear, so they follow the zoom. Each group has its own
  progress spinner.

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
  load does, so the rows never contradict the header's sort arrow.
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

**Scores come from, and go back to, the connector.** A row's four dropdowns are
seeded by the load (`ScoredItem` reads them off the `PrioItem` in its
constructor, before any listener is attached, so seeding is not an edit) and are
sent back on the next save. Nothing else stores them: an item the connector
stops returning simply disappears, and a score PrioLab was never given starts
blank.

The window title is `PrioLab — <project file name>`, with a trailing `*` while
the items are dirty; a document has no name of its own beyond its file. `New
Project`, `Open`, `Exit` and the window's close button all route through
`confirmDiscardItems()`, and the close button is wired via
`stage.setOnCloseRequest(controller::handleCloseRequest)` in `App.showMain()`.

## Project layout

```
build.gradle                       Groovy build, JavaFX + jlink/jpackage/appimage config
settings.gradle
packaging/appimage/                AppImage assets
  AppRun                           entry point -> usr/bin/priolab
  priolab.desktop                  desktop entry (also used for the menu entry)
packaging/windows/priolab.ico      app icon in Windows' own format (jpackage)
examples/connectors/filebased/     a working connector (load + save) to copy
src/main/java/module-info.java     module com.priolab
src/main/java/com/priolab/
  App.java                         Application entry; chooses wizard vs main
  config/Config.java               config.json POJO
  config/ConfigManager.java        load/save ~/.priolab/config.json (Jackson)
  connector/Connector.java         one connector/exporter dir: manifest + child process
  connector/ConnectorManifest.java manifest.json POJO (name/version/author/…/keys)
  connector/ConnectorManager.java  discover connector subdirs in the config'd dir
  controller/WizardController.java first-run wizard
  controller/PluginSettingsController.java  modal key editor (connector or exporter)
  controller/MainController.java   main window, top bar, import/export runs, current Document
  controller/DocumentController.java    center split view: WSJF scoring + result tables
  doc/Document.java                open project: project file + items + dirty
  doc/Project.java                 the .json project file (Jackson bean)
  model/PrioItem.java              one item: id/description/url + UBV/TC/RROE/JS
  model/ScoredItem.java            PrioItem + editable WSJF inputs + computed WSJF
  model/PluginKind.java            CONNECTOR (both ways) vs EXPORTER (one way)
src/main/resources/com/priolab/
  img/priolab.png                  256x256 app icon (stage, jpackage, AppImage)
  img/gear.png                     settings-button icon
  img/load.png                     Load button icon (arrow into a tray)
  img/save.png                     Save button icon (arrow out of a tray)
  img/document.png                 Export button icon (a page, not an arrow)
  fxml/wizard.fxml
  fxml/pluginsettings.fxml
  fxml/main.fxml
  fxml/document.fxml
  css/app.css
```

## Module system notes (important)

- `module-info.java` `opens` the controller package to `javafx.fxml` (FXML
  reflection) and the `config`, `connector` and `doc` packages to
  `com.fasterxml.jackson.databind`.
- **Keep Jackson beans plain.** `doc/Project.java` once had two helper methods
  taking a `PluginKind`; Jackson reflected on the enum and the whole load failed
  with *"module com.priolab does not exports com.priolab.model"*. Mapping a kind
  onto the project's fields belongs in `Document`, not in the bean. The same
  applies to any future POJO: only plain getters and setters over `String`,
  numbers and collections, or the module has to export more than it should.
- Every dependency is modular now (JavaFX and Jackson), so the jlink config
  needs no `forceMerge` / `mergedModule` block. If you ever add a non-modular
  (automatic) jar and jlink fails, that is where it goes — together with a
  `provides` clause for anything it publishes through `ServiceLoader`, which is
  ignored inside a named module.

## Conventions

- Keep the app modular — new packages that FXML or Jackson touch by reflection need
  matching `opens` in `module-info.java`.
- The contract is *environment variables in, one JSON array of items out or in*:
  `operation=load` prints the array on stdout, `operation=save` reads it from
  stdin, an exporter only ever reads it. Keep it that way — no handshake,
  nothing parsed out of stderr, nothing read back from a save or an export —
  and keep the item shape (`id`, `description`, `url` + the four mandatory
  score keys) identical in both directions.
- **Load/Save belong to the connector, Export to the exporter.** The connector
  is persistence, so it borrows the vocabulary of a file; the exporter produces
  an artifact nobody reads back. Do not reintroduce "import" or a second
  "export" button.
- PrioLab stores no items. If something needs to survive a restart and is not
  the connector / exporter choice or their settings, it belongs in the
  connector, not in the project file.
- Connectors and exporters stay interchangeable in everything but direction —
  same manifest, same discovery, same settings dialog. Anything new that applies
  to one should be expressed per `PluginKind` rather than duplicated.
- Sizes in `app.css` are **em**, not px, so Ctrl+scroll zoom scales them; only
  `.root`'s own 13px baseline and a few hairline paddings stay absolute. A new
  `-fx-font-size` in px would simply ignore the zoom. Table rows need
  `-fx-cell-size` in em for the same reason.
- Pin dependency versions in the `ext { }` block in `build.gradle`.
