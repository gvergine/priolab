package com.priolab.controller;

import com.priolab.doc.Document;
import com.priolab.model.ScoredItem;

import javafx.application.HostServices;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Controller for the central editing area (see {@code document.fxml}). The pane
 * is split in two:
 *
 * <ul>
 *   <li><b>Left</b> — a sortable {@link TableView} of the connector's items with
 *       the four WSJF inputs (Business Value, Time Criticality, Risk Reduction,
 *       Job Size) editable per row via dropdowns, plus a computed WSJF column.</li>
 *   <li><b>Right</b> — an unsortable table showing the same items ordered by WSJF
 *       (highest first), with a 1-based Priority column.</li>
 * </ul>
 *
 * Changing any score recomputes that item's WSJF and re-sorts the right table.
 * Each row's {@code id} is a hyperlink that opens the item's {@code url}.
 */
public class DocumentController {

    /** The allowed non-"Undefined" scores (a modified Fibonacci scale). */
    private static final List<Integer> SCORE_OPTIONS =
            List.of(1, 2, 3, 5, 8, 13, 20, 40, 100);

    /** Order the result list by WSJF descending, undefined (null) values last. */
    private static final Comparator<ScoredItem> BY_WSJF_DESC = Comparator.comparing(
            ScoredItem::getWsjf, Comparator.nullsLast(Comparator.reverseOrder()));

    /** Set on left-table rows that are not yet fully scored (see app.css). */
    private static final PseudoClass INCOMPLETE = PseudoClass.getPseudoClass("incomplete");

    @FXML
    private TableView<ScoredItem> leftTable;
    @FXML
    private TableView<ScoredItem> rightTable;

    private HostServices hostServices;
    private Document document;

    /** The result table's backing list, kept sorted by WSJF. */
    private final ObservableList<ScoredItem> rightItems = FXCollections.observableArrayList();

    /** Bind the view to a document and build the two tables' columns. */
    public void init(Document document, HostServices hostServices) {
        this.document = document;
        this.hostServices = hostServices;
        buildLeftTable();
        buildRightTable();
        rightTable.setItems(rightItems);
        showItems();
    }

    /**
     * Show the document's items — exactly what the connector's last load
     * returned, with the scores it delivered already in the dropdowns. The
     * items belong to the {@link Document}; this only renders them.
     */
    public void showItems() {
        List<ScoredItem> items = List.copyOf(document.getItems());
        for (ScoredItem si : items) {
            // Re-sort the result table whenever this item's WSJF changes.
            si.wsjfProperty().addListener((obs, oldVal, newVal) -> resortRight());
        }
        leftTable.getItems().setAll(items);
        // Replacing the items does not re-run an active sort, which would leave
        // the header claiming an order the rows do not have.
        leftTable.sort();
        rightItems.setAll(items);
        resortRight();
    }

    /**
     * The items as the result table shows them: WSJF descending, unscored last.
     * This is the order a save or an export writes them out in.
     */
    public List<ScoredItem> getPrioritizedItems() {
        return List.copyOf(rightItems);
    }

    // --- Table construction --------------------------------------------------

    private void buildLeftTable() {
        leftTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // Light-yellow highlight for any row that is not fully scored yet.
        leftTable.setRowFactory(tv -> new ScoredRow());

        // Every column sorts, ascending and descending: the connector's own
        // order, the key, the description, each WSJF input and the result.
        leftTable.getColumns().setAll(List.of(
                orderColumn(),
                idColumn(),
                descriptionColumn(),
                scoreColumn("UBV", ScoredItem::businessValueProperty),
                scoreColumn("TC", ScoredItem::timeCriticalityProperty),
                scoreColumn("RR/RO", ScoredItem::riskReductionProperty),
                scoreColumn("Size", ScoredItem::jobSizeProperty),
                wsjfColumn()));
    }

    private TableColumn<ScoredItem, Integer> orderColumn() {
        TableColumn<ScoredItem, Integer> col = new TableColumn<>("Order");
        col.setPrefWidth(52);
        col.setMinWidth(44);
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().order()));
        return col;
    }

    private void buildRightTable() {
        rightTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ScoredItem, Void> priority = new TableColumn<>("Priority");
        priority.setSortable(false);
        priority.setPrefWidth(60);
        priority.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void value, boolean empty) {
                super.updateItem(value, empty);
                boolean noRow = getTableRow() == null || getTableRow().getItem() == null;
                setText(empty || noRow ? null : String.valueOf(getIndex() + 1));
            }
        });

        TableColumn<ScoredItem, Double> wsjf = wsjfColumn();
        wsjf.setSortable(false);

        TableColumn<ScoredItem, ScoredItem> id = idColumn();
        id.setSortable(false);

        TableColumn<ScoredItem, String> description = descriptionColumn();
        description.setSortable(false);

        rightTable.getColumns().setAll(List.of(priority, wsjf, id, description));
    }

    private void resortRight() {
        FXCollections.sort(rightItems, BY_WSJF_DESC);
        // Priority cells are index-based, so refresh after reordering.
        rightTable.refresh();
    }

    // --- Column factories ----------------------------------------------------

    /**
     * Comparator for the nullable score / WSJF columns. A blank counts as the
     * lowest value, so ascending lists the unscored items first and descending
     * — the interesting direction — puts them last, behind everything scored,
     * the same way the result table ranks them.
     */
    private static <T extends Comparable<T>> Comparator<T> blankLowest() {
        return Comparator.nullsFirst(Comparator.naturalOrder());
    }

    private TableColumn<ScoredItem, ScoredItem> idColumn() {
        TableColumn<ScoredItem, ScoredItem> col = new TableColumn<>("ID");
        col.setPrefWidth(86);
        col.setMinWidth(60);
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        col.setComparator(Comparator.comparing(
                si -> si.item().id(), Comparator.nullsFirst(Comparator.naturalOrder())));
        col.setCellFactory(c -> new IdCell(hostServices));
        return col;
    }

    private TableColumn<ScoredItem, String> descriptionColumn() {
        TableColumn<ScoredItem, String> col = new TableColumn<>("Description");
        col.setPrefWidth(180);
        col.setMinWidth(70);
        col.setCellValueFactory(cd ->
                new ReadOnlyStringWrapper(cd.getValue().item().description()));
        col.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        return col;
    }

    private TableColumn<ScoredItem, Double> wsjfColumn() {
        TableColumn<ScoredItem, Double> col = new TableColumn<>("WSJF");
        col.setPrefWidth(62);
        col.setMinWidth(50);
        col.setCellValueFactory(cd -> cd.getValue().wsjfProperty());
        col.setComparator(blankLowest());
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.format("%.2f", value));
            }
        });
        return col;
    }

    private TableColumn<ScoredItem, Integer> scoreColumn(
            String title, Function<ScoredItem, ObjectProperty<Integer>> extractor) {
        TableColumn<ScoredItem, Integer> col = new TableColumn<>(title);
        col.setPrefWidth(74);
        // Floor the width: below this the ComboBox has no room to draw the
        // selected number and renders blank, which looks like a lost selection.
        col.setMinWidth(56);
        col.setComparator(blankLowest());
        col.setCellValueFactory(cd -> extractor.apply(cd.getValue()));
        col.setCellFactory(c -> new ScoreCell(extractor));
        return col;
    }

    // --- Cells ---------------------------------------------------------------

    /** A clickable {@code id} that opens the item's {@code url}. */
    private static final class IdCell extends TableCell<ScoredItem, ScoredItem> {

        private final HostServices hostServices;
        private final Hyperlink link = new Hyperlink();
        private ScoredItem current;

        IdCell(HostServices hostServices) {
            this.hostServices = hostServices;
            link.setOnAction(e -> {
                if (current != null && hostServices != null) {
                    String url = current.item().url();
                    if (url != null && !url.isBlank()) {
                        hostServices.showDocument(url);
                    }
                }
            });
        }

        @Override
        protected void updateItem(ScoredItem value, boolean empty) {
            super.updateItem(value, empty);
            current = value;
            if (empty || value == null) {
                setGraphic(null);
                return;
            }
            String id = value.item().id();
            link.setText(id == null || id.isBlank() ? "(no id)" : id);
            String url = value.item().url();
            link.setDisable(url == null || url.isBlank());
            setGraphic(link);
        }
    }

    /** A left-table row highlighted while its item is not fully scored. */
    private static final class ScoredRow extends TableRow<ScoredItem> {

        private final ChangeListener<Double> wsjfListener = (obs, oldVal, newVal) -> refreshStyle();
        private ScoredItem bound;

        @Override
        protected void updateItem(ScoredItem item, boolean empty) {
            super.updateItem(item, empty);
            if (bound != null) {
                bound.wsjfProperty().removeListener(wsjfListener);
                bound = null;
            }
            if (empty || item == null) {
                pseudoClassStateChanged(INCOMPLETE, false);
                return;
            }
            bound = item;
            item.wsjfProperty().addListener(wsjfListener);
            refreshStyle();
        }

        private void refreshStyle() {
            pseudoClassStateChanged(INCOMPLETE, getItem() == null || getItem().getWsjf() == null);
        }
    }

    /** A dropdown of blank (null) plus the score options. */
    private final class ScoreCell extends TableCell<ScoredItem, Integer> {

        private final ComboBox<Integer> combo = new ComboBox<>();
        private final Function<ScoredItem, ObjectProperty<Integer>> extractor;
        private ScoredItem current;
        private boolean updating;

        ScoreCell(Function<ScoredItem, ObjectProperty<Integer>> extractor) {
            this.extractor = extractor;
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.getStyleClass().add("score-combo");
            combo.getItems().add(null); // blank = undefined
            combo.getItems().addAll(SCORE_OPTIONS);
            combo.setConverter(new StringConverter<>() {
                @Override
                public String toString(Integer value) {
                    return value == null ? "" : value.toString();
                }

                @Override
                public Integer fromString(String text) {
                    return text == null || text.isBlank() ? null : Integer.valueOf(text);
                }
            });
            combo.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (!updating && current != null) {
                    extractor.apply(current).set(newVal);
                }
            });
        }

        @Override
        protected void updateItem(Integer value, boolean empty) {
            super.updateItem(value, empty);
            current = getTableRow() == null ? null : getTableRow().getItem();
            if (empty || current == null) {
                setGraphic(null);
                return;
            }
            updating = true;
            combo.setValue(value);
            updating = false;
            setGraphic(combo);
        }
    }
}
