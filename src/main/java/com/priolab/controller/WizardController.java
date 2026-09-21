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
 * First-run wizard: asks the user where the connectors directory lives and
 * writes the initial {@code config.json}.
 */
public class WizardController {

    @FXML
    private TextField connectorsDirField;

    private App app;
    private ConfigManager configManager;

    public void init(App app, ConfigManager configManager) {
        this.app = app;
        this.configManager = configManager;
        // Suggest a sensible default location under ~/.priolab.
        Path suggestion = ConfigManager.CONFIG_DIR.resolve("connectors");
        connectorsDirField.setText(suggestion.toString());
    }

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Connectors Directory");
        String current = connectorsDirField.getText();
        if (current != null && !current.isBlank()) {
            File dir = new File(current);
            if (dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
        }
        File selected = chooser.showDialog(app.getStage());
        if (selected != null) {
            connectorsDirField.setText(selected.getAbsolutePath());
        }
    }

    @FXML
    private void onFinish() {
        String dir = connectorsDirField.getText();
        if (dir == null || dir.isBlank()) {
            alert(Alert.AlertType.WARNING, "Please choose a connectors directory.");
            return;
        }
        Path path = Paths.get(dir);
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "Could not create directory:\n" + e.getMessage());
            return;
        }

        Config config = new Config();
        config.setConnectorsDir(path.toString());
        configManager.save(config);

        app.showMain();
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
