package com.priolab.controller;

import com.priolab.connector.Connector;
import com.priolab.doc.Document;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for the central editing area (see {@code document.fxml}). It edits
 * a single {@link Document}: picking which connector the project uses, and
 * assigning string values to that connector's declared setting keys.
 */
public class DocumentController {

    @FXML
    private ComboBox<String> connectorCombo;
    @FXML
    private Label emptyHint;
    @FXML
    private Label keysTitle;
    @FXML
    private VBox keysBox;

    private Document document;
    private final Map<String, List<String>> keysByConnector = new HashMap<>();

    /**
     * Bind the view to a document and populate the connector choices.
     *
     * @param document   the project being edited
     * @param connectors the currently loaded connectors
     */
    public void init(Document document, List<Connector> connectors) {
        this.document = document;

        List<String> names = new ArrayList<>();
        for (Connector connector : connectors) {
            names.add(connector.getName());
            keysByConnector.put(connector.getName(), connector.getKeys());
        }
        connectorCombo.getItems().setAll(names);

        if (names.isEmpty()) {
            connectorCombo.setDisable(true);
            emptyHint.setVisible(true);
            emptyHint.setManaged(true);
        }

        // Two-way bind so user picks flip the document's dirty flag, and a
        // loaded/saved value shows up preselected.
        connectorCombo.valueProperty().bindBidirectional(
                document.selectedConnectorProperty());
        // Rebuild the per-key inputs whenever the chosen connector changes.
        connectorCombo.valueProperty().addListener(
                (obs, oldVal, newVal) -> rebuildKeys(newVal));
        rebuildKeys(connectorCombo.getValue());
    }

    /** Rebuild the labelled text fields for the selected connector's keys. */
    private void rebuildKeys(String connector) {
        keysBox.getChildren().clear();

        List<String> keys = connector == null
                ? List.of()
                : keysByConnector.getOrDefault(connector, List.of());

        keysTitle.setVisible(!keys.isEmpty());
        keysTitle.setManaged(!keys.isEmpty());

        for (String key : keys) {
            Label label = new Label(key);
            TextField field = new TextField(document.getConnectorSetting(connector, key));
            field.setPromptText(key);
            field.textProperty().addListener((obs, oldVal, newVal) ->
                    document.setConnectorSetting(connector, key, newVal));

            VBox row = new VBox(4, label, field);
            keysBox.getChildren().add(row);
        }
    }
}
