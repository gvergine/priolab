package com.priolab.doc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the user dragged things: the two split dividers and the column widths,
 * remembered per project inside the {@code .json} project file.
 *
 * <p>Like the rest of the project file this is a plain Jackson bean, and
 * changing it never marks the document dirty — it is captured from the UI and
 * written silently when the project is closed.
 *
 * <p>The description columns are deliberately absent: their width is derived
 * from whatever the other columns leave over, so there is nothing to remember.
 */
public class Layout {

    /** Divider of the vertical split while the console is expanded, 0..1. */
    private Double consoleDivider;

    /** Divider between the two item tables, 0..1. */
    private Double tablesDivider;

    /** Column title -> width, for the left ("To prioritize") table. */
    private Map<String, Double> leftColumns = new LinkedHashMap<>();

    /** Column title -> width, for the right ("Result") table. */
    private Map<String, Double> rightColumns = new LinkedHashMap<>();

    public Double getConsoleDivider() {
        return consoleDivider;
    }

    public void setConsoleDivider(Double consoleDivider) {
        this.consoleDivider = consoleDivider;
    }

    public Double getTablesDivider() {
        return tablesDivider;
    }

    public void setTablesDivider(Double tablesDivider) {
        this.tablesDivider = tablesDivider;
    }

    public Map<String, Double> getLeftColumns() {
        return leftColumns;
    }

    public void setLeftColumns(Map<String, Double> leftColumns) {
        this.leftColumns = leftColumns == null ? new LinkedHashMap<>() : leftColumns;
    }

    public Map<String, Double> getRightColumns() {
        return rightColumns;
    }

    public void setRightColumns(Map<String, Double> rightColumns) {
        this.rightColumns = rightColumns == null ? new LinkedHashMap<>() : rightColumns;
    }
}
