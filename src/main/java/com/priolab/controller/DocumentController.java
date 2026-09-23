package com.priolab.controller;

import com.priolab.doc.Document;
import com.priolab.doc.Layout;
import com.priolab.model.ScoredItem;

import javafx.application.HostServices;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
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
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private SplitPane tablesSplit;
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
        // Light-yellow highlight for any row that is not fully scored yet.
        leftTable.setRowFactory(tv -> new ScoredRow());

        // Every column sorts, ascending and descending: the connector's own
        // order, the key, the description, each WSJF input and the result.
        TableColumn<ScoredItem, String> description = descriptionColumn();
        leftTable.getColumns().setAll(List.of(
                orderColumn(),
                idColumn(),
                description,
                scoreColumn("UBV", ScoredItem::businessValueProperty),
                scoreColumn("TC", ScoredItem::timeCriticalityProperty),
                scoreColumn("RROE", ScoredItem::riskReductionProperty),
                scoreColumn("Size", ScoredItem::jobSizeProperty),
                wsjfColumn()));
        giveSlackTo(leftTable, description);
    }

    /**
     * Keep the numeric columns at their (minimum) width and let the description
     * take whatever is left, at every window size.
     *
     * <p>None of JavaFX's constrained resize policies can do this: they hand the
     * slack to the <em>last</em> column, and the description sits in the middle
     * of the left table. So the table is left unconstrained and the description's
     * width is computed from what the others use — which also means the user can
     * still drag any of those others, and the description simply gives way.
     */
    private void giveSlackTo(TableView<ScoredItem> table, TableColumn<ScoredItem, ?> flexible) {
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        List<Observable> dependencies = new ArrayList<>();
        dependencies.add(table.widthProperty());
        for (TableColumn<ScoredItem, ?> column : table.getColumns()) {
            if (column != flexible) {
                dependencies.add(column.widthProperty());
            }
        }
        flexible.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            double used = 0;
            for (TableColumn<ScoredItem, ?> column : table.getColumns()) {
                if (column != flexible) {
                    used += column.getWidth();
                }
            }
            // Leave room for the vertical scrollbar rather than provoke a
            // horizontal one every time the list gets long.
            return Math.max(flexible.getMinWidth(), table.getWidth() - used - 18);
        }, dependencies.toArray(new Observable[0])));
    }

    /** Restore the dividers and column widths the user last left behind. */
    public void applyLayout(Layout layout) {
        if (layout.getTablesDivider() != null) {
            tablesSplit.setDividerPositions(layout.getTablesDivider());
        }
        applyColumnWidths(leftTable, layout.getLeftColumns());
        applyColumnWidths(rightTable, layout.getRightColumns());
    }

    /** Write the current dividers and column widths into {@code layout}. */
    public void captureLayout(Layout layout) {
        layout.setTablesDivider(tablesSplit.getDividerPositions()[0]);
        layout.setLeftColumns(columnWidths(leftTable));
        layout.setRightColumns(columnWidths(rightTable));
    }

    private static void applyColumnWidths(TableView<ScoredItem> table, Map<String, Double> widths) {
        for (TableColumn<ScoredItem, ?> column : table.getColumns()) {
            Double width = widths.get(column.getText());
            // The flexible column's width is bound, and derived anyway.
            if (width != null && width > 0 && !column.prefWidthProperty().isBound()) {
                column.setPrefWidth(width);
            }
        }
    }

    private static Map<String, Double> columnWidths(TableView<ScoredItem> table) {
        Map<String, Double> widths = new LinkedHashMap<>();
        for (TableColumn<ScoredItem, ?> column : table.getColumns()) {
            if (!column.prefWidthProperty().isBound()) {
                widths.put(column.getText(), column.getWidth());
            }
        }
        return widths;
    }

    private TableColumn<ScoredItem, Integer> orderColumn() {
        TableColumn<ScoredItem, Integer> col = new TableColumn<>("Order");
        col.setMinWidth(48);
        col.setPrefWidth(48);
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().order()));
        return col;
    }

    private void buildRightTable() {
        TableColumn<ScoredItem, Void> priority = new TableColumn<>("Priority");
        priority.setSortable(false);
        priority.setMinWidth(62);
        priority.setPrefWidth(62);
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
        giveSlackTo(rightTable, description);
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
        col.setMinWidth(60);
        col.setPrefWidth(110);
        col.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        col.setComparator(Comparator.comparing(
                si -> si.item().id(), Comparator.nullsFirst(Comparator.naturalOrder())));
        col.setCellFactory(c -> new IdCell(hostServices));
        return col;
    }

    private TableColumn<ScoredItem, String> descriptionColumn() {
        TableColumn<ScoredItem, String> col = new TableColumn<>("Description");
        // Small enough that the narrower result pane still fits its four
        // columns without a horizontal scrollbar.
        col.setMinWidth(80);
        col.setCellValueFactory(cd ->
                new ReadOnlyStringWrapper(cd.getValue().item().description()));
        col.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        return col;
    }

    private TableColumn<ScoredItem, Double> wsjfColumn() {
        TableColumn<ScoredItem, Double> col = new TableColumn<>("WSJF");
        col.setMinWidth(56);
        col.setPrefWidth(56);
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
        col.setPrefWidth(58);
        // Floor the width: below this the ComboBox has no room to draw the
        // selected number and renders blank, which looks like a lost selection.
        col.setMinWidth(58);
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
