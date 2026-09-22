package com.priolab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main window controller. Owns the currently open {@link Document}, drives the
 * File menu (new/open/save) and keeps the title/status in sync, prompting to
 * save whenever unsaved edits would be lost.
 *
 * <p>It also owns the top bar's two program groups — the <b>connector</b>
 * (Import) and the <b>exporter</b> (Export). Both are discovered the same way
 * from their own configured directory, and both are launched with their
 * per-project settings in the environment; they differ only in the direction
 * the items flow: a connector prints them on stdout, an exporter is fed them on
 * stdin (see {@link PluginKind}).
 */
public class MainController {

    @FXML
    private Label statusLabel;
    @FXML
    private StackPane contentPane;
    @FXML
    private TextArea logArea;
    @FXML
    private Button importButton;
    @FXML
    private Button connectorSettingsButton;
    @FXML
    private ProgressIndicator importProgress;
    @FXML
    private HBox connectorBar;
    @FXML
    private ComboBox<String> connectorCombo;
    @FXML
    private Button exportButton;
    @FXML
    private Button exporterSettingsButton;
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

    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** Phases of the connector's stdout as a run is read. */
    private enum RunPhase { IDLE, AWAIT_COUNT, READ_ITEMS, DONE }

    private RunPhase runPhase = RunPhase.IDLE;
    private int itemsRemaining;
    private List<PrioItem> collectedItems;

    public void init(App app, ConfigManager configManager) {
        this.app = app;
        this.configManager = configManager;
        String connectorsDir = configManager.get().getConnectorsDir();
        String exportersDir = configManager.get().getExportersDir();
        managers.put(PluginKind.CONNECTOR, new ConnectorManager(
                connectorsDir == null ? null : Paths.get(connectorsDir)));
        managers.put(PluginKind.EXPORTER, new ConnectorManager(
                exportersDir == null ? null : Paths.get(exportersDir)));
        setStatus("Ready — connectors: " + connectorsDir + " · exporters: " + exportersDir);
    }

    @FXML
    private void onNewProject() {
        if (!maybeSaveCurrent()) {
            return;
        }
        NewProjectController.Result result;
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/priolab/fxml/newproject.fxml"));
            Parent root = loader.load();
            NewProjectController controller = loader.getController();

            Stage dialog = new Stage();
            dialog.initOwner(app.getStage());
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("New Project");
            App.applyIcon(dialog);
            Scene scene = new Scene(root, 480, 300);
            scene.getStylesheets().add(
                    getClass().getResource("/com/priolab/css/app.css").toExternalForm());
            dialog.setScene(scene);
            controller.setStage(dialog);
            dialog.showAndWait();

            result = controller.getResult();
        } catch (IOException e) {
            error("Failed to open the New Project dialog:\n" + e.getMessage());
            return;
        }
        if (result == null) {
            return; // cancelled
        }

        try {
            Path parent = result.file().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            adoptDocument(Document.create(result.file(), result.name()));
            setStatus("Created project \"" + result.name() + "\" — "
                    + result.file().toAbsolutePath());
        } catch (Exception e) {
            error("Failed to create project:\n" + e.getMessage());
        }
    }

    @FXML
    private void onOpen() {
        if (!maybeSaveCurrent()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Project");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "SQLite Database", "*.sqlite3", "*.sqlite", "*.db"));
        File file = chooser.showOpenDialog(app.getStage());
        if (file == null) {
            return;
        }
        try {
            adoptDocument(Document.open(file.toPath()));
            setStatus("Opened: " + file.getAbsolutePath());
        } catch (Exception e) {
            error("Failed to open project:\n" + e.getMessage());
        }
    }

    @FXML
    private void onSave() {
        if (document == null) {
            setStatus("Nothing to save — open or create a project first.");
            return;
        }
        saveCurrent();
    }

    @FXML
    private void onExit() {
        if (!maybeSaveCurrent()) {
            return;
        }
        shutdown();
        Platform.exit();
    }

    /** Wired as the window close handler; cancels the close on unsaved edits. */
    public void handleCloseRequest(WindowEvent event) {
        if (!maybeSaveCurrent()) {
            event.consume();
            return;
        }
        shutdown();
    }

    /** Release resources: stop any running connector and close the document. */
    private void shutdown() {
        for (Connector plugin : running.values()) {
            plugin.close();
        }
        running.clear();
        if (document != null) {
            document.close();
        }
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "PrioLab\n\nA tool to help you prioritize anything, "
                        + "powered by pluggable connectors.");
        alert.setHeaderText("About PrioLab");
        alert.setTitle("About");
        App.applyIcon(alert);
        alert.showAndWait();
    }

    /** Run the selected connector and load what it prints into the tables. */
    @FXML
    private void onImport() {
        Connector connector = selectedPlugin(PluginKind.CONNECTOR);
        if (connector == null) {
            return;
        }
        appendLog("$ " + connector.getName() + " — " + connector.getManifest().getCommand());

        // Reset the parse state and clear any previously loaded items.
        runPhase = RunPhase.AWAIT_COUNT;
        itemsRemaining = 0;
        collectedItems = null;
        if (documentController != null) {
            documentController.setItems(List.of());
        }

        Map<String, String> env = buildEnvironment(PluginKind.CONNECTOR, connector);
        logEnvironment(env);
        try {
            connector.run(
                    env,
                    line -> Platform.runLater(() -> handleConnectorLine(line)),
                    line -> Platform.runLater(() -> appendLog(line)),
                    code -> Platform.runLater(() -> {
                        appendLog("[exited with code " + code + "]");
                        running.remove(PluginKind.CONNECTOR);
                        runPhase = RunPhase.IDLE;
                        setRunning(PluginKind.CONNECTOR, false);
                    }));
            running.put(PluginKind.CONNECTOR, connector);
            setRunning(PluginKind.CONNECTOR, true);
            setStatus("Importing with connector: " + connector.getName());
        } catch (Exception e) {
            error("Failed to run connector:\n" + e.getMessage());
            runPhase = RunPhase.IDLE;
        }
    }

    /**
     * Run the selected exporter, feeding it the items currently shown — in
     * priority order — as one JSON object per line on its stdin. Each object
     * carries the item as the connector delivered it plus the four WSJF inputs
     * and the computed WSJF ({@code null} while a score is blank).
     */
    @FXML
    private void onExport() {
        Connector exporter = selectedPlugin(PluginKind.EXPORTER);
        if (exporter == null) {
            return;
        }
        List<ScoredItem> items = documentController == null
                ? List.of() : documentController.getPrioritizedItems();
        if (items.isEmpty()) {
            setStatus("Nothing to export — import some items first.");
            return;
        }
        List<String> lines = new ArrayList<>(items.size());
        for (ScoredItem item : items) {
            lines.add(toExportJson(item));
        }

        appendLog("$ " + exporter.getName() + " — " + exporter.getManifest().getCommand());
        Map<String, String> env = buildEnvironment(PluginKind.EXPORTER, exporter);
        logEnvironment(env);
        appendLog("[writing " + lines.size() + " item(s) to stdin]");
        try {
            exporter.run(
                    env,
                    lines,
                    line -> Platform.runLater(() -> appendLog(line)),
                    line -> Platform.runLater(() -> appendLog(line)),
                    code -> Platform.runLater(() -> {
                        appendLog("[exited with code " + code + "]");
                        running.remove(PluginKind.EXPORTER);
                        setRunning(PluginKind.EXPORTER, false);
                        setStatus(code == 0
                                ? "Exported " + lines.size() + " item(s)."
                                : "Exporter failed with exit code " + code + ".");
                    }));
            running.put(PluginKind.EXPORTER, exporter);
            setRunning(PluginKind.EXPORTER, true);
            setStatus("Exporting with: " + exporter.getName());
        } catch (Exception e) {
            error("Failed to run exporter:\n" + e.getMessage());
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

    /** One JSON line for an exporter: the item, its four inputs and its WSJF. */
    private String toExportJson(ScoredItem scored) {
        PrioItem item = scored.item();
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("id", item.id());
        node.put("description", item.description());
        node.put("url", item.url());
        node.put("businessValue", scored.businessValueProperty().get());
        node.put("timeCriticality", scored.timeCriticalityProperty().get());
        node.put("riskReduction", scored.riskReductionProperty().get());
        node.put("jobSize", scored.jobSizeProperty().get());
        Double wsjf = scored.getWsjf();
        // Two decimals, exactly the value the result table shows.
        node.put("wsjf", wsjf == null ? null : Math.round(wsjf * 100) / 100.0);
        return node.toString();
    }

    /**
     * Read the connector's stdout as it arrives (on the FX thread): the first
     * non-blank line is the number of items, followed by that many JSON item
     * objects. Anything the connector prints afterwards is only logged.
     */
    private void handleConnectorLine(String line) {
        appendLog(line);
        String trimmed = line.trim();
        switch (runPhase) {
            case AWAIT_COUNT -> {
                if (trimmed.isEmpty()) {
                    return;
                }
                try {
                    itemsRemaining = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    appendLog("[expected an item count but got: " + line + "]");
                    runPhase = RunPhase.DONE;
                    return;
                }
                collectedItems = new ArrayList<>();
                if (itemsRemaining <= 0) {
                    publishItems();
                    runPhase = RunPhase.DONE;
                } else {
                    runPhase = RunPhase.READ_ITEMS;
                }
            }
            case READ_ITEMS -> {
                if (trimmed.isEmpty()) {
                    return;
                }
                PrioItem item = parseItem(trimmed);
                if (item != null) {
                    collectedItems.add(item);
                }
                if (--itemsRemaining <= 0) {
                    publishItems();
                    runPhase = RunPhase.DONE;
                }
            }
            default -> {
                // IDLE / DONE: nothing to parse, output is just logged.
            }
        }
    }

    /** Parse one item JSON object, or {@code null} (logging) if it is malformed. */
    private PrioItem parseItem(String line) {
        try {
            JsonNode node = jsonMapper.readTree(line);
            return new PrioItem(
                    node.path("id").asText(""),
                    node.path("description").asText(""),
                    node.path("url").asText(""));
        } catch (IOException e) {
            appendLog("[invalid item JSON: " + e.getMessage() + "]");
            return null;
        }
    }

    /** Push the collected items into the split lists and update the status. */
    private void publishItems() {
        List<PrioItem> items = collectedItems == null ? List.of() : collectedItems;
        if (documentController != null) {
            documentController.setItems(items);
        }
        setStatus("Loaded " + items.size() + " item(s) to prioritize.");
    }

    /**
     * The environment the command is launched with: one variable per manifest
     * key, named exactly like the key and holding the value configured for this
     * project (empty when the user has not set one). They are added to the
     * environment PrioLab itself was started with.
     */
    private Map<String, String> buildEnvironment(PluginKind kind, Connector plugin) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : plugin.getKeys()) {
            String value = document.getSetting(kind, plugin.getName(), key);
            env.put(key, value == null ? "" : value);
        }
        return env;
    }

    private void logEnvironment(Map<String, String> env) {
        env.forEach((key, value) -> appendLog("  " + key + "=" + value));
    }

    /**
     * Reflect a run in the UI: while the program runs, disable that group's
     * inputs and show its spinning indeterminate progress indicator.
     */
    private void setRunning(PluginKind kind, boolean busy) {
        if (kind == PluginKind.CONNECTOR) {
            importButton.setDisable(busy);
            connectorCombo.setDisable(busy);
            connectorSettingsButton.setDisable(busy);
            importProgress.setVisible(busy);
            importProgress.setManaged(busy);
        } else {
            exportButton.setDisable(busy);
            exporterCombo.setDisable(busy);
            exporterSettingsButton.setDisable(busy);
            exportProgress.setVisible(busy);
            exportProgress.setManaged(busy);
        }
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
            connectorCombo.valueProperty().unbindBidirectional(
                    document.selectedProperty(PluginKind.CONNECTOR));
            exporterCombo.valueProperty().unbindBidirectional(
                    document.selectedProperty(PluginKind.EXPORTER));
            document.close();
        }
        document = next;
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

    /** Persist the current document; returns true on success. */
    private boolean saveCurrent() {
        if (document == null) {
            return true;
        }
        try {
            document.save();
            updateTitle();
            setStatus("Saved: " + document.getPath().toAbsolutePath());
            return true;
        } catch (Exception e) {
            error("Failed to save:\n" + e.getMessage());
            return false;
        }
    }

    /**
     * If the current document has unsaved edits, ask what to do. Returns true if
     * the caller may proceed (saved or discarded), false if the user cancelled.
     */
    private boolean maybeSaveCurrent() {
        if (document == null || !document.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Save changes to \"" + displayName() + "\" before continuing?",
                ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
        alert.setHeaderText(null);
        alert.setTitle("Unsaved Changes");
        App.applyIcon(alert);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return false;
        }
        if (choice.get() == ButtonType.YES) {
            return saveCurrent();
        }
        return true; // NO -> discard
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

    private String displayName() {
        if (document == null) {
            return "";
        }
        if (document.getName() != null && !document.getName().isBlank()) {
            return document.getName();
        }
        return document.getPath().getFileName().toString();
    }

    private void updateTitle() {
        if (document == null) {
            app.getStage().setTitle("PrioLab");
            return;
        }
        app.getStage().setTitle(
                "PrioLab — " + displayName() + (document.isDirty() ? " *" : ""));
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
