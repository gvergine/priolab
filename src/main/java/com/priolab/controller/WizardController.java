package com.priolab.controller;

import com.priolab.App;
import com.priolab.config.Config;
import com.priolab.config.ConfigManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * First-run wizard: asks the user where the connectors and exporters
 * directories live and writes {@code config.json}. It also runs again for a
 * config written before exporters existed, prefilled with what is already set,
 * so the missing directory can be filled in.
 */
public class WizardController {

    @FXML
    private TextField connectorsDirField;
    @FXML
    private TextField exportersDirField;

    private App app;
    private ConfigManager configManager;

    public void init(App app, ConfigManager configManager) {
        this.app = app;
        this.configManager = configManager;
        Config existing = configManager.get();
        connectorsDirField.setText(valueOrDefault(
                existing == null ? null : existing.getConnectorsDir(), "connectors"));
        exportersDirField.setText(valueOrDefault(
                existing == null ? null : existing.getExportersDir(), "exporters"));
    }

    /** Keep what the config already holds, else suggest {@code ~/.priolab/<name>}. */
    private static String valueOrDefault(String current, String name) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return ConfigManager.CONFIG_DIR.resolve(name).toString();
    }

    @FXML
    private void onBrowseConnectors() {
        browse("Select Connectors Directory", connectorsDirField);
    }

    @FXML
    private void onBrowseExporters() {
        browse("Select Exporters Directory", exportersDirField);
    }

    private void browse(String title, TextField field) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        String current = field.getText();
        if (current != null && !current.isBlank()) {
            File dir = new File(current);
            if (dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
        }
        File selected = chooser.showDialog(app.getStage());
        if (selected != null) {
            field.setText(selected.getAbsolutePath());
        }
    }

    @FXML
    private void onFinish() {
        Path connectors = validated(connectorsDirField.getText(), "connectors");
        if (connectors == null) {
            return;
        }
        Path exporters = validated(exportersDirField.getText(), "exporters");
        if (exporters == null) {
            return;
        }

        Config config = configManager.get() == null ? new Config() : configManager.get();
        config.setConnectorsDir(connectors.toString());
        config.setExportersDir(exporters.toString());
        configManager.save(config);

        app.showMain();
    }

    /** Check one directory field and create the directory; null if unusable. */
    private Path validated(String dir, String what) {
        if (dir == null || dir.isBlank()) {
            alert(Alert.AlertType.WARNING, "Please choose a " + what + " directory.");
            return null;
        }
        Path path = Paths.get(dir);
        try {
            Files.createDirectories(path);
            return path;
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "Could not create directory:\n" + e.getMessage());
            return null;
        }
    }

    @FXML
    private void onCancel() {
        Platform.exit();
    }

    private void alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.setHeaderText(null);
        App.applyIcon(alert);
        alert.showAndWait();
    }
}
