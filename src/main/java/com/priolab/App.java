package com.priolab;

import com.priolab.config.ConfigManager;
import com.priolab.controller.MainController;
import com.priolab.controller.WizardController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PrioLab application entry point.
 *
 * <p>On startup we look for {@code ~/.priolab/config.json}. If it is missing we
 * treat this as a fresh install and show the configuration wizard; otherwise we
 * load the config and open the main window.
 */
public class App extends Application {

    private Stage stage;
    private final ConfigManager configManager = new ConfigManager();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("PrioLab");

        if (configManager.configExists()) {
            configManager.load();
            showMain();
        } else {
            showWizard();
        }
        stage.show();
    }

    /** Show the first-run configuration wizard. */
    public void showWizard() {
        WizardController controller = swapScene("/com/priolab/fxml/wizard.fxml", 540, 380);
        controller.init(this, configManager);
    }

    /** Show the main application window. */
    public void showMain() {
        MainController controller = swapScene("/com/priolab/fxml/main.fxml", 960, 640);
        controller.init(this, configManager);
        // Route the window's close button through the same unsaved-changes guard.
        stage.setOnCloseRequest(controller::handleCloseRequest);
    }

    private <T> T swapScene(String fxml, int width, int height) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            Parent root = loader.load();
            Scene scene = stage.getScene();
            if (scene == null) {
                scene = new Scene(root, width, height);
                scene.getStylesheets().add(
                        getClass().getResource("/com/priolab/css/app.css").toExternalForm());
                stage.setScene(scene);
            } else {
                scene.setRoot(root);
                stage.setWidth(width);
                stage.setHeight(height);
            }
            return loader.getController();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load view: " + fxml, e);
        }
    }

    public Stage getStage() {
        return stage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
