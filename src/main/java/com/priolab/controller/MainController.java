package com.priolab.controller;

import com.priolab.App;
import com.priolab.config.ConfigManager;
import com.priolab.connector.Connector;
import com.priolab.connector.ConnectorManager;
import com.priolab.doc.Document;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
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

    private App app;
    private ConfigManager configManager;
    private ConnectorManager connectorManager;

    private Document document;
    private Connector runningConnector;

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
        try {
            connector.run(
                    line -> Platform.runLater(() -> appendLog(line)),
                    code -> Platform.runLater(() -> {
                        appendLog("[exited with code " + code + "]");
                        runningConnector = null;
                        runButton.setDisable(false);
                    }));
            runningConnector = connector;
            runButton.setDisable(true);
            setStatus("Running connector: " + name);
        } catch (Exception e) {
            error("Failed to run connector:\n" + e.getMessage());
        }
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
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
            document.close();
        }
        document = next;
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/priolab/fxml/document.fxml"));
            Parent root = loader.load();
            DocumentController controller = loader.getController();
            controller.init(document, connectorNames());
            contentPane.getChildren().setAll(root);
        } catch (IOException e) {
            error("Failed to load the editor view:\n" + e.getMessage());
            return;
        }
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
        try {
            return connectorManager.discover().stream()
                    .map(Connector::getName)
                    .toList();
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
