package com.priolab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.priolab.App;
import com.priolab.config.ConfigManager;
import com.priolab.connector.Connector;
import com.priolab.connector.ConnectorManager;
import com.priolab.connector.Protocol;
import com.priolab.doc.Document;
import com.priolab.model.PrioItem;
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
import java.util.List;
import java.util.Optional;

/**
 * Main window controller. Owns the currently open {@link Document}, drives the
 * File menu (new/open/save) and keeps the title/status in sync, prompting to
 * save whenever unsaved edits would be lost.
 */
public class MainController {

    @FXML
    private Label statusLabel;
    @FXML
    private StackPane contentPane;
    @FXML
    private TextArea logArea;
    @FXML
    private Button runButton;
    @FXML
    private Button connectorSettingsButton;
    @FXML
    private ProgressIndicator runProgress;
    @FXML
    private HBox connectorBar;
    @FXML
    private ComboBox<String> connectorCombo;

    private App app;
    private ConfigManager configManager;
    private ConnectorManager connectorManager;

    private Document document;
    private DocumentController documentController;
    private Connector runningConnector;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** Phases of the line-oriented connector protocol during a run. */
    private enum RunPhase { IDLE, AWAIT_ACK, AWAIT_COUNT, READ_ITEMS, DONE }

    private RunPhase runPhase = RunPhase.IDLE;
    private int itemsRemaining;
    private List<PrioItem> collectedItems;

    public void init(App app, ConfigManager configManager) {
        this.app = app;
        this.configManager = configManager;
        String connectorsDir = configManager.get().getConnectorsDir();
        this.connectorManager = new ConnectorManager(
                connectorsDir == null ? null : Paths.get(connectorsDir));
        setStatus("Ready — connectors: " + connectorsDir);
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
        if (runningConnector != null) {
            runningConnector.close();
            runningConnector = null;
        }
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
        alert.showAndWait();
    }

    @FXML
    private void onRunConnector() {
        if (document == null) {
            setStatus("Open or create a project first.");
            return;
        }
        String name = document.selectedConnectorProperty().get();
        if (name == null || name.isBlank()) {
            setStatus("Select a connector to run.");
            return;
        }
        if (runningConnector != null && runningConnector.isRunning()) {
            setStatus("A connector is already running.");
            return;
        }
        Connector connector = findConnector(name);
        if (connector == null) {
            error("Connector not found: " + name
                    + "\nIt may have been removed or its manifest is invalid.");
            return;
        }
        appendLog("$ " + name + " — " + connector.getManifest().getCommand());

        // Reset the parse state and clear any previously loaded items.
        runPhase = RunPhase.AWAIT_ACK;
        itemsRemaining = 0;
        collectedItems = null;
        if (documentController != null) {
            documentController.setItems(List.of());
        }

        String initLine = buildInitLine(connector);
        try {
            connector.run(
                    line -> Platform.runLater(() -> handleConnectorLine(connector, line)),
                    code -> Platform.runLater(() -> {
                        appendLog("[exited with code " + code + "]");
                        runningConnector = null;
                        runPhase = RunPhase.IDLE;
                        setRunning(false);
                    }));
            runningConnector = connector;
            setRunning(true);
            setStatus("Running connector: " + name);
            // Hand the connector its per-project settings straight away.
            appendLog("> " + initLine);
            sendLine(connector, initLine);
        } catch (Exception e) {
            error("Failed to run connector:\n" + e.getMessage());
            runPhase = RunPhase.IDLE;
        }
    }

    /**
     * Drive the line-oriented protocol as the connector's output arrives (on the
     * FX thread). After {@code INIT}, the first line must be {@link Protocol#OK};
     * otherwise nothing further happens and the connector's error stays in the
     * log. On OK we send {@link Protocol#GET}; the connector then replies with a
     * count line followed by that many JSON item objects.
     */
    private void handleConnectorLine(Connector connector, String line) {
        appendLog(line);
        String trimmed = line.trim();
        switch (runPhase) {
            case AWAIT_ACK -> {
                if (trimmed.isEmpty()) {
                    return;
                }
                if (Protocol.OK.equals(trimmed)) {
                    runPhase = RunPhase.AWAIT_COUNT;
                    appendLog("> " + Protocol.GET);
                    sendLine(connector, Protocol.GET);
                } else {
                    // INIT was not acknowledged; the error is already in the log.
                    runPhase = RunPhase.DONE;
                }
            }
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

    /** Build the {@code INIT key=value ...} line from the document's settings. */
    private String buildInitLine(Connector connector) {
        StringBuilder sb = new StringBuilder(Protocol.INIT);
        for (String key : connector.getKeys()) {
            sb.append(' ').append(key).append('=')
                    .append(document.getConnectorSetting(connector.getName(), key));
        }
        return sb.toString();
    }

    /**
     * Reflect the connector run state in the UI: while running, disable the
     * connector inputs and show the spinning indeterminate progress indicator.
     */
    private void setRunning(boolean running) {
        runButton.setDisable(running);
        connectorCombo.setDisable(running);
        connectorSettingsButton.setDisable(running);
        runProgress.setVisible(running);
        runProgress.setManaged(running);
    }

    /** Write a line to the connector's stdin, surfacing any failure in the log. */
    private void sendLine(Connector connector, String line) {
        try {
            connector.send(line);
        } catch (IOException e) {
            appendLog("[failed to write to connector: " + e.getMessage() + "]");
        }
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
    }

    /** Open the modal dialog to edit the selected connector's setting values. */
    @FXML
    private void onConnectorSettings() {
        if (document == null) {
            return;
        }
        String name = connectorCombo.getValue();
        if (name == null || name.isBlank()) {
            setStatus("Select a connector to configure.");
            return;
        }
        Connector connector = findConnector(name);
        if (connector == null) {
            error("Connector not found: " + name
                    + "\nIt may have been removed or its manifest is invalid.");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/priolab/fxml/connectorsettings.fxml"));
            Parent root = loader.load();
            ConnectorSettingsController controller = loader.getController();

            Stage dialog = new Stage();
            dialog.initOwner(app.getStage());
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Connector Settings");
            App.applyIcon(dialog);
            Scene scene = new Scene(root, 420, 380);
            scene.getStylesheets().add(
                    getClass().getResource("/com/priolab/css/app.css").toExternalForm());
            dialog.setScene(scene);
            controller.setStage(dialog);
            controller.init(document, connector);
            dialog.showAndWait();
        } catch (IOException e) {
            error("Failed to open connector settings:\n" + e.getMessage());
        }
    }

    private Connector findConnector(String name) {
        try {
            return connectorManager.discover().stream()
                    .filter(c -> c.getName().equals(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            error("Failed to list connectors:\n" + e.getMessage());
            return null;
        }
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
                    document.selectedConnectorProperty());
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

        // Populate and bind the connector selector in the top bar.
        connectorCombo.getItems().setAll(connectorNames());
        connectorCombo.valueProperty().bindBidirectional(
                document.selectedConnectorProperty());
        connectorBar.setDisable(false);

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
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return false;
        }
        if (choice.get() == ButtonType.YES) {
            return saveCurrent();
        }
        return true; // NO -> discard
    }

    private List<String> connectorNames() {
        return connectors().stream().map(Connector::getName).toList();
    }

    private List<Connector> connectors() {
        try {
            return connectorManager.discover();
        } catch (IOException e) {
            error("Failed to list connectors:\n" + e.getMessage());
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
        alert.showAndWait();
    }
}
