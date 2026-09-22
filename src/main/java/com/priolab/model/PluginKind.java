package com.priolab.model;

/**
 * The two roles an external program can play for PrioLab. Both are the same
 * kind of directory — a {@code manifest.json} plus a command — and are
 * configured by the user in the same way; they differ only in which directory
 * they are discovered in and in how PrioLab talks to them:
 *
 * <ul>
 *   <li>{@link #CONNECTOR} — the importer: it <em>prints</em> the items to
 *       prioritize on stdout.</li>
 *   <li>{@link #EXPORTER} — the counterpart: it <em>reads</em> the prioritized
 *       items from stdin.</li>
 * </ul>
 *
 * The kind also selects which per-project table the user's setting values live
 * in, so a connector and an exporter that happen to share a name keep their own
 * settings.
 */
public enum PluginKind {

    CONNECTOR("connector"),
    EXPORTER("exporter");

    private final String label;

    PluginKind(String label) {
        this.label = label;
    }

    /** Lower-case name used in messages, {@code meta} keys and table names. */
    public String label() {
        return label;
    }
}
