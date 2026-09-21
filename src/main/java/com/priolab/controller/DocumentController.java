package com.priolab.controller;

import com.priolab.doc.Document;
import com.priolab.model.PrioItem;

import javafx.application.HostServices;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.util.Callback;

import java.util.List;

/**
 * Controller for the central editing area (see {@code document.fxml}). The pane
 * is split in two: the left list holds the connector's items to prioritize, and
 * the right list will hold the prioritization result. For now both show the same
 * items; the prioritization interaction on the left (and its outcome on the
 * right) will be built out later.
 *
 * <p>Each row renders the item {@code id} as a hyperlink that opens the item's
 * {@code url}, followed by its {@code description}.
 */
public class DocumentController {

    @FXML
    private ListView<PrioItem> leftList;
    @FXML
    private ListView<PrioItem> rightList;

    private HostServices hostServices;

    /** Bind the view to a document and prepare the item lists. */
    public void init(Document document, HostServices hostServices) {
        this.hostServices = hostServices;
        leftList.setCellFactory(itemCellFactory());
        rightList.setCellFactory(itemCellFactory());
    }

    /** Replace the items shown in both lists. */
    public void setItems(List<PrioItem> items) {
        leftList.getItems().setAll(items);
        rightList.getItems().setAll(items);
    }

    private Callback<ListView<PrioItem>, ListCell<PrioItem>> itemCellFactory() {
        return list -> new ItemCell(hostServices);
    }

    /** Renders an item as a clickable id (opens its url) plus its description. */
    private static final class ItemCell extends ListCell<PrioItem> {

        private final HostServices hostServices;
        private final Hyperlink idLink = new Hyperlink();
        private final Label description = new Label();
        private final HBox box = new HBox(8, idLink, description);

        private PrioItem current;

        ItemCell(HostServices hostServices) {
            this.hostServices = hostServices;
            box.setAlignment(Pos.CENTER_LEFT);
            idLink.setOnAction(e -> {
                if (current != null && current.url() != null && !current.url().isBlank()
                        && hostServices != null) {
                    hostServices.showDocument(current.url());
                }
            });
        }

        @Override
        protected void updateItem(PrioItem item, boolean empty) {
            super.updateItem(item, empty);
            current = item;
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            idLink.setText(item.id() == null || item.id().isBlank() ? "(no id)" : item.id());
            idLink.setDisable(item.url() == null || item.url().isBlank());
            description.setText(item.description() == null ? "" : item.description());
            setGraphic(box);
        }
    }
}
