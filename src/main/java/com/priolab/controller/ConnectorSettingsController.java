package com.priolab.controller;

import com.priolab.connector.Connector;
import com.priolab.doc.Document;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal dialog for editing the per-project values of a connector's declared
 * setting keys (see {@code connectorsettings.fxml}). Opened from the connector
 * settings icon in the main window. On OK it writes each field back into the
 * {@link Document}; Cancel discards the edits.
 */
public class ConnectorSettingsController {

    @FXML
    private Label headerLabel;
    @FXML
    private VBox keysBox;
    @FXML
    private Label emptyHint;

    private Stage stage;
    private Document document;
    private Connector connector;

    /** Text field per key, in declaration order, for read-back on OK. */
    private final Map<String, TextField> fields = new LinkedHashMap<>();

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /** Populate the dialog with a field per key, seeded from the document. */
    public void init(Document document, Connector connector) {
        this.document = document;
        this.connector = connector;
        headerLabel.setText("Settings — " + connector.getName());

        List<String> keys = connector.getKeys();
        if (keys.isEmpty()) {
            emptyHint.setVisible(true);
            emptyHint.setManaged(true);
            return;
        }

        for (String key : keys) {
            Label label = new Label(key);
            TextField field = new TextField(
                    document.getConnectorSetting(connector.getName(), key));
            field.setPromptText(key);
            fields.put(key, field);
            keysBox.getChildren().add(new VBox(4, label, field));
        }
    }

    @FXML
    private void onOk() {
        for (Map.Entry<String, TextField> entry : fields.entrySet()) {
            document.setConnectorSetting(
                    connector.getName(), entry.getKey(), entry.getValue().getText());
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
