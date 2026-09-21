package com.priolab.controller;

import com.priolab.doc.Document;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

/**
 * Controller for the central editing area (see {@code document.fxml}). It shows
 * a summary of the open {@link Document}; the connector selection and its
 * settings are edited from the toolbar in the main window.
 */
public class DocumentController {

    @FXML
    private Label nameLabel;
    @FXML
    private Label connectorLabel;

    /** Bind the view to a document. */
    public void init(Document document) {
        String name = document.getName();
        nameLabel.setText(name == null || name.isBlank() ? "Untitled project" : name);

        connectorLabel.textProperty().bind(document.selectedConnectorProperty().map(
                value -> "Connector: " + (value == null || value.isBlank() ? "none" : value)));
    }
}
