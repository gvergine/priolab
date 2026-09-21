package com.priolab.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Small modal wizard for creating a new project. A project is a SQLite file;
 * the user must supply a name and a file location. On success
 * {@link #getResult()} returns the chosen name and target file; on cancel it
 * returns {@code null}.
 */
public class NewProjectController {

    /** Outcome of a completed wizard: the project name and target file. */
    public record Result(String name, Path file) {}

    @FXML
    private TextField nameField;
    @FXML
    private TextField fileField;

    private Stage stage;
    private Result result;

    /** Injected by {@code MainController} after loading the FXML. */
    void setStage(Stage stage) {
        this.stage = stage;
    }

    /** The wizard result, or {@code null} if it was cancelled/closed. */
    Result getResult() {
        return result;
    }

    @FXML
    private void onBrowse() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Project File Location");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "SQLite Database", "*.sqlite3", "*.sqlite", "*.db"));

        String current = fileField.getText();
        if (current != null && !current.isBlank()) {
            File f = new File(current);
            File parent = f.getParentFile();
            if (parent != null && parent.isDirectory()) {
                chooser.setInitialDirectory(parent);
            }
            chooser.setInitialFileName(f.getName());
        } else {
            String name = nameField.getText();
            chooser.setInitialFileName(
                    (name == null || name.isBlank() ? "project" : sanitize(name)) + ".sqlite3");
        }

        File selected = chooser.showSaveDialog(stage);
        if (selected != null) {
            fileField.setText(selected.getAbsolutePath());
        }
    }

    @FXML
    private void onCreate() {
        String name = nameField.getText();
        if (name == null || name.isBlank()) {
            alert(Alert.AlertType.WARNING, "Please enter a project name.");
            return;
        }
        String file = fileField.getText();
        if (file == null || file.isBlank()) {
            alert(Alert.AlertType.WARNING, "Please choose a file location.");
            return;
        }

        // Default the extension so we always end up with a recognisable file.
        if (!file.matches("(?i).*\\.(sqlite3|sqlite|db)$")) {
            file = file + ".sqlite3";
        }
        Path path = Paths.get(file);

        if (Files.exists(path)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "A file already exists at:\n" + path
                            + "\n\nIt will be reused. Continue?");
            confirm.setHeaderText(null);
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get() != ButtonType.OK) {
                return;
            }
        }

        result = new Result(name.trim(), path);
        stage.close();
    }

    @FXML
    private void onCancel() {
        result = null;
        stage.close();
    }

    /** Reduce a project name to a safe default filename stem. */
    private static String sanitize(String name) {
        return name.trim().replaceAll("[^a-zA-Z0-9-_ ]", "").replace(' ', '_');
    }

    private void alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
