package com.priolab.controller;

import com.priolab.App;
import com.priolab.config.ConfigManager;
import com.priolab.connector.ConnectorManager;
import com.priolab.db.Database;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Paths;
import java.sql.SQLException;

/**
 * Main window controller. The central work area is intentionally empty for now;
 * the menu exposes opening/saving a SQLite database.
 */
public class MainController {

    @FXML
    private Label statusLabel;

    private App app;
    private ConfigManager configManager;
    private ConnectorManager connectorManager;
    private final Database database = new Database();

    public void init(App app, ConfigManager configManager) {
        this.app = app;
        this.configManager = configManager;
        String connectorsDir = configManager.get().getConnectorsDir();
        this.connectorManager = new ConnectorManager(
                connectorsDir == null ? null : Paths.get(connectorsDir));
        setStatus("Ready — connectors: " + connectorsDir);
    }

    @FXML
    private void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open SQLite Database");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "SQLite Database", "*.sqlite3", "*.sqlite", "*.db"));
        File file = chooser.showOpenDialog(app.getStage());
        if (file == null) {
            return;
        }
        try {
            database.open(file.toPath());
            setStatus("Opened: " + file.getAbsolutePath());
        } catch (SQLException e) {
            error("Failed to open database:\n" + e.getMessage());
        }
    }

    @FXML
    private void onSave() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save SQLite Database");
        chooser.setInitialFileName("priolab.sqlite3");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "SQLite Database", "*.sqlite3", "*.sqlite", "*.db"));
        File file = chooser.showSaveDialog(app.getStage());
        if (file == null) {
            return;
        }
        try {
            // Opening a connection creates the file if it does not exist.
            database.open(file.toPath());
            setStatus("Saved: " + file.getAbsolutePath());
        } catch (SQLException e) {
            error("Failed to save database:\n" + e.getMessage());
        }
    }

    @FXML
    private void onExit() {
        database.close();
        Platform.exit();
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
