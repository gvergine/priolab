package com.priolab.controller;

import com.priolab.connector.Connector;
import com.priolab.doc.Document;
import com.priolab.model.PluginKind;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal dialog for editing the per-project values of a connector's or
 * exporter's declared setting keys (see {@code pluginsettings.fxml}). Opened
 * from either ⚙ button in the main window's top bar; the {@link PluginKind}
 * decides which settings are read and written. On OK it writes each field back
 * into the {@link Document}; Cancel discards the edits.
 */
public class PluginSettingsController {

    @FXML
    private Label headerLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private VBox keysBox;
    @FXML
    private Label emptyHint;

    private Stage stage;
    private Document document;
    private PluginKind kind;
    private Connector plugin;

    /** Text field per key, in declaration order, for read-back on OK. */
    private final Map<String, TextField> fields = new LinkedHashMap<>();

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /** Populate the dialog with a field per key, seeded from the document. */
    public void init(Document document, PluginKind kind, Connector plugin) {
        this.document = document;
        this.kind = kind;
        this.plugin = plugin;
        headerLabel.setText("Settings — " + plugin.getName());
        subtitleLabel.setText("Assign values to this " + kind.label()
                + "'s settings. They are saved into the project file and passed"
                + " to its command as environment variables.");

        List<String> keys = plugin.getKeys();
        if (keys.isEmpty()) {
            emptyHint.setText("This " + kind.label() + " declares no settings.");
            emptyHint.setVisible(true);
            emptyHint.setManaged(true);
            return;
        }

        for (String key : keys) {
            Label label = new Label(key);
            TextField field = new TextField(
                    document.getSetting(kind, plugin.getName(), key));
            field.setPromptText(key);
            fields.put(key, field);
            keysBox.getChildren().add(new VBox(4, label, field));
        }
    }

    @FXML
    private void onOk() {
        for (Map.Entry<String, TextField> entry : fields.entrySet()) {
            document.setSetting(
                    kind, plugin.getName(), entry.getKey(), entry.getValue().getText());
        }
        close();
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        if (stage != null) {
            stage.close();
        }
    }
}
