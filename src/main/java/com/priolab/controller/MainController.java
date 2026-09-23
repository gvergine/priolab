package com.priolab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.priolab.App;
import com.priolab.config.ConfigManager;
import com.priolab.connector.Connector;
import com.priolab.connector.ConnectorManager;
import com.priolab.doc.Document;
import com.priolab.model.PluginKind;
import com.priolab.model.PrioItem;
import com.priolab.model.ScoredItem;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Main window controller. Owns the currently open {@link Document}, drives the
 * File menu (new/open) and keeps the title/status in sync.
 *
 * <p>It also owns the top bar's two program groups:
 *
 * <ul>
 *   <li>the <b>connector</b>, which is where the items live — <b>Load</b> runs
 *       it with {@code operation=load} and reads the JSON array it prints,
 *       <b>Save</b> runs it with {@code operation=save} and feeds the same
 *       array to its stdin;</li>
 *   <li>the <b>exporter</b>, a one-way sink that turns the same array into a
 *       file (a spreadsheet, a PDF) and gives nothing back.</li>
 * </ul>
 *
 * Both kinds are discovered the same way from their own configured directory
 * and launched with their per-project settings in the environment (see
 * {@link PluginKind}).
 */
public class MainController {

    /** Root font size at 100% zoom; matches {@code .root} in app.css. */
    private static final double BASE_FONT_SIZE = 13;

    private static final double MIN_ZOOM = 0.6;
    private static final double MAX_ZOOM = 2.5;
    private static final double ZOOM_STEP = 1.1;

    /** The console takes the bottom fifth of the window when it is open. */
    private static final double DEFAULT_CONSOLE_DIVIDER = 0.8;

    @FXML
    private BorderPane rootPane;
    @FXML
    private MenuBar menuBar;
    @FXML
    private Label statusLabel;
    @FXML
    private StackPane contentPane;
    @FXML
    private SplitPane mainSplit;
    @FXML
    private Accordion consoleAccordion;
    @FXML
    private TitledPane consolePane;
    @FXML
    private TextArea logArea;
    @FXML
    private Button loadButton;
    @FXML
    private ImageView loadIcon;
    @FXML
    private Button saveButton;
    @FXML
    private ImageView saveIcon;
    @FXML
    private Button connectorSettingsButton;
    @FXML
    private ImageView connectorSettingsIcon;
    @FXML
    private ProgressIndicator connectorProgress;
    @FXML
    private HBox connectorBar;
    @FXML
    private ComboBox<String> connectorCombo;
    @FXML
    private Button exportButton;
    @FXML
    private ImageView exportIcon;
    @FXML
    private Button exporterSettingsButton;
    @FXML
    private ImageView exporterSettingsIcon;
    @FXML
    private ProgressIndicator exportProgress;
    @FXML
    private HBox exporterBar;
    @FXML
    private ComboBox<String> exporterCombo;

    private App app;
    private ConfigManager configManager;

    /** One discovery manager per kind, over its own configured directory. */
    private final Map<PluginKind, ConnectorManager> managers = new EnumMap<>(PluginKind.class);

    /** The process currently running for each kind, if any. */
    private final Map<PluginKind, Connector> running = new EnumMap<>(PluginKind.class);

    private Document document;
    private DocumentController documentController;

    /** Current UI scale, 1.0 = 100% (see {@link #installZoom()}). */
    private double zoom = 1.0;

    /** Where the vertical divider sits while the console is open, 0..1. */
    private double consoleDivider = DEFAULT_CONSOLE_DIVIDER;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Everything a load's stdout produced. A connector prints one JSON array
     * for the whole run, which may well be pretty-printed across many lines, so
     * the text is collected here and parsed once the process exits.
     */
    private StringBuilder loadOutput;

    public void init(App app, ConfigManager configManager) {
        this.app = app;
        this.configManager = configManager;
        String connectorsDir = configManager.get().getConnectorsDir();
        String exportersDir = configManager.get().getExportersDir();
        managers.put(PluginKind.CONNECTOR, new ConnectorManager(
                connectorsDir == null ? null : Paths.get(connectorsDir)));
        managers.put(PluginKind.EXPORTER, new ConnectorManager(
                exportersDir == null ? null : Paths.get(exportersDir)));
        installZoom();
        installConsole();
        sizeWithFont(connectorSettingsIcon, connectorSettingsButton);
        sizeWithFont(exporterSettingsIcon, exporterSettingsButton);
        sizeWithFont(loadIcon, loadButton);
        sizeWithFont(saveIcon, saveButton);
        sizeWithFont(exportIcon, exportButton);
        setStatus("Ready — connectors: " + connectorsDir + " · exporters: " + exportersDir);
        reopenLastProject();
    }

    /**
     * Reopen the project that was open when PrioLab last exited. The project
     * file holds only the connector / exporter choice and their settings, so
     * this is a convenience, never a source of items: those come from an import.
     */
    private void reopenLastProject() {
        String last = configManager.get().getLastProjectFile();
        if (last == null || last.isBlank()) {
            return;
        }
        Path path = Paths.get(last);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            adoptDocument(Document.open(path));
            setStatus("Opened " + path.toAbsolutePath());
        } catch (Exception e) {
            appendLog("[could not reopen " + path + ": " + e.getMessage() + "]");
        }
    }

    /**
     * Zoom the whole window with <b>Ctrl+scroll</b> (and Ctrl+plus / Ctrl+minus
     * / Ctrl+0 to reset).
     *
     * <p>It works by setting {@code -fx-font-size} on the window's root: every
     * size in app.css is expressed in {@code em} and JavaFX's own control
     * styling is font-relative, so the whole UI — text, paddings, table rows —
     * grows and <em>relayouts</em> with it. That is why this is preferred over
     * {@code Node.scaleX/scaleY}, which would merely magnify a fixed layout and
     * push the rest of the window out of view.
     *
     * <p>The handlers are event <em>filters</em> on the root, so a Ctrl+scroll
     * over the tables or the console zooms instead of scrolling them.
     */
    private void installZoom() {
        rootPane.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isShortcutDown() || event.getDeltaY() == 0) {
                return;
            }
            applyZoom(event.getDeltaY() > 0 ? zoom * ZOOM_STEP : zoom / ZOOM_STEP);
            event.consume();
        });
        rootPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown()) {
                return;
            }
            switch (event.getCode()) {
                case PLUS, ADD, EQUALS -> applyZoom(zoom * ZOOM_STEP);
                case MINUS, SUBTRACT -> applyZoom(zoom / ZOOM_STEP);
                case DIGIT0, NUMPAD0 -> applyZoom(1.0);
                default -> {
                    return;
                }
            }
            event.consume();
        });
    }

    /**
     * The console is an accordion that stays shut until it has something to say.
     *
     * <p>Collapsed, the pane is capped at its own preferred height, so the split
     * divider sits right under the header and cannot be dragged open by
     * accident; expanded, the cap is lifted and the divider goes back where the
     * user last left it. Opening or closing it is otherwise entirely the user's
     * business — PrioLab only ever opens it, never closes it (see
     * {@link #launch}).
     */
    private void installConsole() {
        SplitPane.setResizableWithParent(consoleAccordion, false);
        consolePane.expandedProperty().addListener((obs, was, expanded) -> {
            if (!expanded) {
                // Remember how tall the user had made it.
                consoleDivider = mainSplit.getDividerPositions()[0];
            }
            syncConsole();
        });
        syncConsole();
    }

    private void syncConsole() {
        if (consolePane.isExpanded()) {
            consoleAccordion.setMaxHeight(Double.MAX_VALUE);
            mainSplit.setDividerPositions(consoleDivider);
        } else {
            consoleAccordion.setMaxHeight(Region.USE_PREF_SIZE);
        }
    }

    /** Open the console because something went wrong; never close it. */
    private void revealConsole() {
        if (!consolePane.isExpanded()) {
            consolePane.setExpanded(true);
        }
    }

    /**
     * Tie a button's image to its font size, so the gear grows and shrinks with
     * the zoom exactly like a text label would.
     */
    private static void sizeWithFont(ImageView icon, Button button) {
        var size = Bindings.createDoubleBinding(
                () -> Math.rint(button.getFont().getSize() * 1.15), button.fontProperty());
        icon.fitWidthProperty().bind(size);
        icon.fitHeightProperty().bind(size);
    }

    /** Clamp, remember and apply a new zoom level. */
    private void applyZoom(double factor) {
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, factor));
        if (Math.abs(next - zoom) < 0.001) {
            return;
        }
        zoom = next;
        // Locale.ROOT: a comma decimal separator would not parse as CSS.
        rootPane.setStyle(String.format(
                Locale.ROOT, "-fx-font-size: %.2fpx;", BASE_FONT_SIZE * zoom));
        setStatus("Zoom: " + Math.round(zoom * 100) + "%");
    }

    @FXML
    private void onNewProject() {
        if (!confirmDiscardItems()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("New Project");
        chooser.setInitialFileName("project.json");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PrioLab project", "*.json"));
        File file = chooser.showSaveDialog(app.getStage());
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            path = path.resolveSibling(path.getFileName() + ".json");
        }
        try {
            adoptDocument(Document.create(path));
            setStatus("Created " + path.toAbsolutePath());
        } catch (Exception e) {
            error("Failed to create the project file:\n" + e.getMessage());
        }
    }

    @FXML
    private void onOpen() {
        if (!confirmDiscardItems()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Project");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PrioLab project", "*.json"));
        File file = chooser.showOpenDialog(app.getStage());
        if (file == null) {
            return;
        }
        try {
            adoptDocument(Document.open(file.toPath()));
            setStatus("Opened " + file.getAbsolutePath());
        } catch (Exception e) {
            error("Failed to open the project file:\n" + e.getMessage());
        }
    }

    @FXML
    private void onExit() {
        if (!confirmDiscardItems()) {
            return;
        }
        shutdown();
        Platform.exit();
    }

    /** Wired as the window close handler; cancels the close on un-exported items. */
    public void handleCloseRequest(WindowEvent event) {
        if (!confirmDiscardItems()) {
            event.consume();
            return;
        }
        shutdown();
    }

    /**
     * Release resources on the way out: stop anything still running and write
     * the project file. There is no Save — the connector choice, the exporter
     * choice and their settings are simply persisted here, silently.
     */
    private void shutdown() {
        for (Connector plugin : running.values()) {
            plugin.close();
        }
        running.clear();
        saveProjectQuietly();
    }

    /** Write the current project file, reporting a failure only in the log. */
    private void saveProjectQuietly() {
        if (document == null) {
            return;
        }
        captureLayout();
        try {
            document.save();
        } catch (Exception e) {
            appendLog("[could not write " + document.getPath() + ": " + e.getMessage() + "]");
        }
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "Version " + App.version() + "\n\n"
                        + "A tool to help you prioritize anything, powered by pluggable "
                        + "connectors and exporters.\n\n"
                        + "Giovanni Vergine\n"
                        + "verginegiovanni@gmail.com");
        alert.setHeaderText("PrioLab");
        alert.setTitle("About");
        App.applyIcon(alert);
        alert.showAndWait();
    }

    /**
     * Run the connector with {@code operation=load} and replace the items with
     * the JSON array it prints. Whatever is on screen is overwritten, so the
     * user is asked first.
     */
    @FXML
    private void onConnectorLoad() {
        Connector connector = selectedPlugin(PluginKind.CONNECTOR);
        if (connector == null) {
            return;
        }
        if (!document.getItems().isEmpty() && !confirmReplaceItems()) {
            return;
        }
        loadOutput = new StringBuilder();
        setStatus("Loading from connector: " + connector.getName());
        launch(PluginKind.CONNECTOR, connector,
                environment(PluginKind.CONNECTOR, connector, Connector.OPERATION_LOAD),
                null,
                code -> finishLoad(connector, code));
    }

    /** Parse what the load printed and hand it to the document. */
    private void finishLoad(Connector connector, int code) {
        String output = loadOutput == null ? "" : loadOutput.toString();
        loadOutput = null;
        if (code != 0) {
            setStatus(connector.getName() + " failed to load — exit code " + code + ".");
            return;
        }
        List<PrioItem> items = parseItems(output);
        if (items == null) {
            revealConsole();
            setStatus("Load failed — see the console.");
            return;
        }
        document.loadItems(items);
        if (documentController != null) {
            documentController.showItems();
        }
        setStatus("Loaded " + items.size() + " item(s) from " + connector.getName()
                + (document.isDirty() ? " — they replaced what was shown before." : "."));
    }

    /**
     * Run the connector with {@code operation=save}, feeding it the items as one
     * JSON array on stdin. A clean exit means the connector has taken them, so
     * the document is no longer dirty.
     */
    @FXML
    private void onConnectorSave() {
        Connector connector = selectedPlugin(PluginKind.CONNECTOR);
        if (connector == null) {
            return;
        }
        if (document.getItems().isEmpty()) {
            setStatus("Nothing to save — load some items first.");
            return;
        }
        int count = document.getItems().size();
        setStatus("Saving through connector: " + connector.getName());
        launch(PluginKind.CONNECTOR, connector,
                environment(PluginKind.CONNECTOR, connector, Connector.OPERATION_SAVE),
                itemsAsJson(),
                code -> {
                    if (code != 0) {
                        setStatus(connector.getName() + " failed to save — exit code "
                                + code + ".");
                        return;
                    }
                    document.markSaved();
                    setStatus("Saved " + count + " item(s) through " + connector.getName()
                            + ".");
                });
    }

    /**
     * Run the selected exporter, feeding it the same JSON array on stdin. An
     * exporter only ever produces a file — a spreadsheet, a PDF — and gives
     * nothing back, so however it goes it says nothing about whether the
     * connector holds the items: the dirty flag is left alone.
     */
    @FXML
    private void onExport() {
        Connector exporter = selectedPlugin(PluginKind.EXPORTER);
        if (exporter == null) {
            return;
        }
        if (document.getItems().isEmpty()) {
            setStatus("Nothing to export — load some items first.");
            return;
        }
        int count = document.getItems().size();
        setStatus("Exporting with: " + exporter.getName());
        launch(PluginKind.EXPORTER, exporter,
                environment(PluginKind.EXPORTER, exporter, null),
                itemsAsJson(),
                code -> setStatus(code == 0
                        ? "Exported " + count + " item(s) through " + exporter.getName() + "."
                        : exporter.getName() + " failed — exit code " + code + "."));
    }

    /**
     * Start one program and wire its output to the console. stdout is also
     * collected while an import is in flight; {@code onExit} runs on the FX
     * thread once the process is gone and the UI has been released.
     */
    private void launch(PluginKind kind, Connector plugin, Map<String, String> env,
                        String stdin, IntConsumer onExit) {
        appendLog("$ " + plugin.getName() + " — " + plugin.getManifest().getCommand());
        logEnvironment(env);
        if (stdin != null) {
            appendLog("[writing " + document.getItems().size() + " item(s) to stdin]");
        }
        try {
            plugin.run(env, stdin,
                    line -> Platform.runLater(() -> {
                        appendLog(line);
                        if (loadOutput != null) {
                            loadOutput.append(line).append('\n');
                        }
                    }),
                    line -> Platform.runLater(() -> appendLog(line)),
                    code -> Platform.runLater(() -> {
                        appendLog("[exited with code " + code + "]");
                        running.remove(kind);
                        setRunning(kind, false);
                        if (code != 0) {
                            revealConsole();
                        }
                        onExit.accept(code);
                    }));
            running.put(kind, plugin);
            setRunning(kind, true);
        } catch (Exception e) {
            loadOutput = null;
            error("Failed to run " + kind.label() + ":\n" + e.getMessage());
        }
    }

    /**
     * The connector / exporter the user picked, or {@code null} (with a status
     * message) when there is nothing to run: no project, no selection, one
     * already running, or a name that no longer resolves.
     */
    private Connector selectedPlugin(PluginKind kind) {
        if (document == null) {
            setStatus("Open or create a project first.");
            return null;
        }
        String name = document.selectedProperty(kind).get();
        if (name == null || name.isBlank()) {
            setStatus("Select a " + kind.label() + " to run.");
            return null;
        }
        Connector current = running.get(kind);
        if (current != null && current.isRunning()) {
            setStatus("A " + kind.label() + " is already running.");
            return null;
        }
        Connector plugin = findPlugin(kind, name);
        if (plugin == null) {
            error(kind.label() + " not found: " + name
                    + "\nIt may have been removed or its manifest is invalid.");
        }
        return plugin;
    }

    /**
     * Parse a load: one JSON array of item objects, each carrying {@code id},
     * {@code description}, {@code url} and the four mandatory score keys.
     * Returns {@code null} when the output is not usable at all; individual
     * problems are logged and the item kept with blanks.
     */
    private List<PrioItem> parseItems(String output) {
        String text = output.trim();
        if (text.isEmpty()) {
            appendLog("[the connector printed nothing on stdout]");
            return null;
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(text);
        } catch (IOException e) {
            appendLog("[the connector's output is not valid JSON: " + e.getMessage() + "]");
            return null;
        }
        if (root == null || !root.isArray()) {
            appendLog("[expected a JSON array of items]");
            return null;
        }
        List<PrioItem> items = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject()) {
                appendLog("[skipped an entry that is not a JSON object]");
                continue;
            }
            String id = node.path("id").asText("");
            items.add(new PrioItem(
                    id,
                    node.path("description").asText(""),
                    node.path("url").asText(""),
                    score(node, PrioItem.KEY_UBV, id),
                    score(node, PrioItem.KEY_TC, id),
                    score(node, PrioItem.KEY_RROE, id),
                    score(node, PrioItem.KEY_JS, id)));
        }
        return items;
    }

    /**
     * One score off a loaded item: {@code null}, or a string holding the
     * number. The key is mandatory, so a missing one is worth saying out loud
     * even though the item is still taken.
     */
    private Integer score(JsonNode node, String key, String id) {
        if (!node.has(key)) {
            appendLog("[" + id + ": missing \"" + key + "\" — treated as blank]");
            return null;
        }
        JsonNode value = node.get(key);
        if (value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            appendLog("[" + id + ": \"" + key + "\" is not a number (" + text
                    + ") — treated as blank]");
            return null;
        }
    }

    /**
     * The items as one JSON array, in priority order, for whatever reads them
     * on stdin — a connector save or an exporter. Scores go out the way they
     * came in: a string holding the number, or {@code null}.
     */
    private String itemsAsJson() {
        ArrayNode array = jsonMapper.createArrayNode();
        List<ScoredItem> ordered = documentController == null
                ? List.copyOf(document.getItems()) : documentController.getPrioritizedItems();
        for (ScoredItem scored : ordered) {
            PrioItem item = scored.item();
            ObjectNode node = array.addObject();
            node.put("id", item.id());
            node.put("description", item.description());
            node.put("url", item.url());
            putScore(node, PrioItem.KEY_UBV, scored.businessValueProperty().get());
            putScore(node, PrioItem.KEY_TC, scored.timeCriticalityProperty().get());
            putScore(node, PrioItem.KEY_RROE, scored.riskReductionProperty().get());
            putScore(node, PrioItem.KEY_JS, scored.jobSizeProperty().get());
        }
        return array.toString();
    }

    private static void putScore(ObjectNode node, String key, Integer value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, String.valueOf(value));
        }
    }

    /**
     * The environment the command is launched with: one variable per manifest
     * key, named exactly like the key and holding the value configured for this
     * project (empty when the user has not set one), plus {@code operation} for
     * a connector. They are added to the environment PrioLab itself was started
     * with.
     */
    private Map<String, String> environment(PluginKind kind, Connector plugin, String operation) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : plugin.getKeys()) {
            String value = document.getSetting(kind, plugin.getName(), key);
            env.put(key, value == null ? "" : value);
        }
        if (operation != null) {
            env.put(Connector.ENV_OPERATION, operation);
        }
        return env;
    }

    private void logEnvironment(Map<String, String> env) {
        env.forEach((key, value) -> appendLog("  " + key + "=" + value));
    }

    /**
     * Reflect a run in the UI. A run owns the window: the menu, <em>both</em>
     * program groups and the item tables are disabled until the process exits,
     * so nothing can be re-run, re-selected, reconfigured or edited underneath
     * it. Only the running kind's indeterminate spinner is shown; the console
     * stays live so the output can be watched (and cleared).
     */
    private void setRunning(PluginKind kind, boolean busy) {
        menuBar.setDisable(busy);
        connectorBar.setDisable(busy || document == null);
        exporterBar.setDisable(busy || document == null);
        contentPane.setDisable(busy);

        ProgressIndicator spinner =
                kind == PluginKind.CONNECTOR ? connectorProgress : exportProgress;
        spinner.setVisible(busy);
        spinner.setManaged(busy);
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
    }

    /** Open the modal dialog to edit the selected connector's setting values. */
    @FXML
    private void onConnectorSettings() {
        openSettings(PluginKind.CONNECTOR);
    }

    /** Open the modal dialog to edit the selected exporter's setting values. */
    @FXML
    private void onExporterSettings() {
        openSettings(PluginKind.EXPORTER);
    }

    private void openSettings(PluginKind kind) {
        if (document == null) {
            setStatus("Open or create a project first.");
            return;
        }
        String name = document.selectedProperty(kind).get();
        if (name == null || name.isBlank()) {
            setStatus("Select a " + kind.label() + " to configure.");
            return;
        }
        Connector plugin = findPlugin(kind, name);
        if (plugin == null) {
            error(kind.label() + " not found: " + name
                    + "\nIt may have been removed or its manifest is invalid.");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/priolab/fxml/pluginsettings.fxml"));
            Parent root = loader.load();
            PluginSettingsController controller = loader.getController();

            Stage dialog = new Stage();
            dialog.initOwner(app.getStage());
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle(kind == PluginKind.CONNECTOR
                    ? "Connector Settings" : "Exporter Settings");
            App.applyIcon(dialog);
            Scene scene = new Scene(root, 420, 380);
            scene.getStylesheets().add(
                    getClass().getResource("/com/priolab/css/app.css").toExternalForm());
            dialog.setScene(scene);
            controller.setStage(dialog);
            controller.init(document, kind, plugin);
            dialog.showAndWait();
        } catch (IOException e) {
            error("Failed to open " + kind.label() + " settings:\n" + e.getMessage());
        }
    }

    /** Look up one connector / exporter by name in its own directory. */
    private Connector findPlugin(PluginKind kind, String name) {
        return plugins(kind).stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private void appendLog(String line) {
        if (logArea != null) {
            logArea.appendText(line + "\n");
        }
    }

    /** Replace the current document with {@code next}, wiring up the editor. */
    private void adoptDocument(Document next) {
        if (document != null) {
            saveProjectQuietly();
            connectorCombo.valueProperty().unbindBidirectional(
                    document.selectedProperty(PluginKind.CONNECTOR));
            exporterCombo.valueProperty().unbindBidirectional(
                    document.selectedProperty(PluginKind.EXPORTER));
        }
        document = next;
        rememberProject(next.getPath());
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/priolab/fxml/document.fxml"));
            Parent root = loader.load();
            documentController = loader.getController();
            documentController.init(document, app.getHostServices());
            contentPane.getChildren().setAll(root);
        } catch (IOException e) {
            error("Failed to load the editor view:\n" + e.getMessage());
            return;
        }

        // Put the dividers and column widths back where this project left them.
        consoleDivider = document.getLayout().getConsoleDivider() == null
                ? DEFAULT_CONSOLE_DIVIDER : document.getLayout().getConsoleDivider();
        syncConsole();
        documentController.applyLayout(document.getLayout());

        // Populate and bind the two selectors in the top bar.
        connectorCombo.getItems().setAll(pluginNames(PluginKind.CONNECTOR));
        connectorCombo.valueProperty().bindBidirectional(
                document.selectedProperty(PluginKind.CONNECTOR));
        exporterCombo.getItems().setAll(pluginNames(PluginKind.EXPORTER));
        exporterCombo.valueProperty().bindBidirectional(
                document.selectedProperty(PluginKind.EXPORTER));
        connectorBar.setDisable(false);
        exporterBar.setDisable(false);

        document.dirtyProperty().addListener((obs, was, dirty) -> updateTitle());
        updateTitle();
    }

    /** Take the sizes the user dragged into the project file, ready to be saved. */
    private void captureLayout() {
        if (document == null) {
            return;
        }
        if (consolePane.isExpanded()) {
            consoleDivider = mainSplit.getDividerPositions()[0];
        }
        document.getLayout().setConsoleDivider(consoleDivider);
        if (documentController != null) {
            documentController.captureLayout(document.getLayout());
        }
    }

    /** Remember this project so the next start reopens it. */
    private void rememberProject(Path path) {
        try {
            configManager.get().setLastProjectFile(path.toAbsolutePath().toString());
            configManager.save(configManager.get());
        } catch (Exception e) {
            appendLog("[could not record the last project: " + e.getMessage() + "]");
        }
    }

    /**
     * Ask before a load throws away what is on screen. The items only exist in
     * memory until the connector saves them, so this is the one warning that
     * matters.
     */
    private boolean confirmReplaceItems() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Loading replaces the " + document.getItems().size()
                        + " item(s) currently shown"
                        + (document.isDirty() ? ", including changes that have not been "
                                + "saved" : "")
                        + ".\n\nContinue?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(null);
        alert.setTitle("Replace items");
        App.applyIcon(alert);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /**
     * Ask before closing a document whose items have not been saved. Only the
     * connector can take them, so the choice is to go on or stay.
     */
    private boolean confirmDiscardItems() {
        if (document == null || !document.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "The items in \"" + document.getName() + "\" have changes that have not "
                        + "been saved through the connector.\n\nContinue and lose them?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(null);
        alert.setTitle("Not saved");
        App.applyIcon(alert);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private List<String> pluginNames(PluginKind kind) {
        return plugins(kind).stream().map(Connector::getName).toList();
    }

    /** Every valid connector / exporter found in that kind's directory. */
    private List<Connector> plugins(PluginKind kind) {
        try {
            return managers.get(kind).discover();
        } catch (IOException e) {
            error("Failed to list " + kind.label() + "s:\n" + e.getMessage());
            return List.of();
        }
    }

    /** The title is the project file's name, with a * while items are unsaved. */
    private void updateTitle() {
        if (document == null) {
            app.getStage().setTitle("PrioLab");
            return;
        }
        app.getStage().setTitle(
                "PrioLab — " + document.getName() + (document.isDirty() ? " *" : ""));
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        App.applyIcon(alert);
        alert.showAndWait();
    }
}
