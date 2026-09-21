package com.priolab.controller;

import com.priolab.doc.Document;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.util.List;

/**
 * Controller for the central editing area (see {@code document.fxml}). It edits
 * a single {@link Document}; for now that means picking which connector the
 * project uses from the connectors currently loaded.
 */
public class DocumentController {

    @FXML
    private ComboBox<String> connectorCombo;
    @FXML
    private Label emptyHint;

    /**
     * Bind the view to a document and populate the connector choices.
     *
     * @param document       the project being edited
     * @param connectorNames names of the currently loaded connectors
     */
    public void init(Document document, List<String> connectorNames) {
        connectorCombo.getItems().setAll(connectorNames);

        if (connectorNames.isEmpty()) {
            connectorCombo.setDisable(true);
            emptyHint.setVisible(true);
            emptyHint.setManaged(true);
        }

        // Two-way bind so user picks flip the document's dirty flag, and a
        // loaded/saved value shows up preselected.
        connectorCombo.valueProperty().bindBidirectional(
                document.selectedConnectorProperty());
    }
}
