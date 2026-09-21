package com.priolab.doc;

import com.priolab.db.Database;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An open PrioLab project: the SQLite {@link Database} plus the in-memory,
 * editable state layered on top of it.
 *
 * <p>The editable fields are the name of the selected connector (stored in the
 * {@code meta} table under the key {@code connector}) and the per-connector
 * setting values the user assigns to the connector's declared keys (stored in
 * the {@code connector_settings} table). The {@link #dirtyProperty() dirty} flag
 * tracks whether any in-memory value has diverged from what is persisted, so the
 * UI can prompt to save.
 */
public class Document implements AutoCloseable {

    /** {@code meta} key under which the selected connector name is stored. */
    private static final String KEY_CONNECTOR = "connector";

    private final Database database;
    private final Path path;
    private String name;

    private final StringProperty selectedConnector = new SimpleStringProperty();
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    /** The last value written to (or read from) disk, for dirty comparison. */
    private String savedConnector;

    /**
     * Per-connector setting values, {@code connector -> (key -> value)}. Empty
     * values are normalised away (absent), so the two maps compare cleanly for
     * the dirty check. {@code savedSettings} mirrors what is on disk;
     * {@code editSettings} holds the live in-memory edits.
     */
    private Map<String, Map<String, String>> savedSettings = new HashMap<>();
    private Map<String, Map<String, String>> editSettings = new HashMap<>();

    private Document(Database database, Path path) {
        this.database = database;
        this.path = path;
        selectedConnector.addListener((obs, oldVal, newVal) -> recomputeDirty());
    }

    /** Create a brand-new project file and open it as a document. */
    public static Document create(Path file, String name) throws SQLException {
        Database db = new Database();
        db.createProject(file, name);
        Document doc = new Document(db, file);
        doc.load();
        return doc;
    }

    /** Open an existing SQLite file as a document (creating schema if needed). */
    public static Document open(Path file) throws SQLException {
        Database db = new Database();
        db.open(file);
        db.ensureSchema();
        Document doc = new Document(db, file);
        doc.load();
        return doc;
    }

    /** (Re)load the editable state from disk and clear the dirty flag. */
    private void load() throws SQLException {
        this.name = database.getProjectName();
        this.savedConnector = database.getMeta(KEY_CONNECTOR);
        selectedConnector.set(savedConnector);
        this.savedSettings = database.getAllConnectorSettings();
        this.editSettings = deepCopy(savedSettings);
        dirty.set(false);
    }

    /** Persist the in-memory edits back to the SQLite file. */
    public void save() throws SQLException {
        String value = selectedConnector.get();
        database.putMeta(KEY_CONNECTOR, value);
        savedConnector = value;

        // Upsert every current setting; delete any that were removed.
        for (var connectorEntry : editSettings.entrySet()) {
            for (var keyEntry : connectorEntry.getValue().entrySet()) {
                database.putConnectorSetting(
                        connectorEntry.getKey(), keyEntry.getKey(), keyEntry.getValue());
            }
        }
        for (var connectorEntry : savedSettings.entrySet()) {
            Map<String, String> current =
                    editSettings.getOrDefault(connectorEntry.getKey(), Map.of());
            for (String key : connectorEntry.getValue().keySet()) {
                if (!current.containsKey(key)) {
                    database.deleteConnectorSetting(connectorEntry.getKey(), key);
                }
            }
        }

        savedSettings = deepCopy(editSettings);
        dirty.set(false);
    }

    /** The in-memory value assigned to {@code key} of {@code connector} ({@code ""} if unset). */
    public String getConnectorSetting(String connector, String key) {
        Map<String, String> forConnector = editSettings.get(connector);
        String value = forConnector == null ? null : forConnector.get(key);
        return value == null ? "" : value;
    }

    /**
     * Assign a value to {@code key} of {@code connector}. Blank values are
     * treated as unset. Updates the {@link #dirtyProperty() dirty} flag.
     */
    public void setConnectorSetting(String connector, String key, String value) {
        if (value == null || value.isEmpty()) {
            Map<String, String> forConnector = editSettings.get(connector);
            if (forConnector != null) {
                forConnector.remove(key);
                if (forConnector.isEmpty()) {
                    editSettings.remove(connector);
                }
            }
        } else {
            editSettings.computeIfAbsent(connector, c -> new HashMap<>()).put(key, value);
        }
        recomputeDirty();
    }

    private void recomputeDirty() {
        dirty.set(!Objects.equals(selectedConnector.get(), savedConnector)
                || !editSettings.equals(savedSettings));
    }

    private static Map<String, Map<String, String>> deepCopy(
            Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return copy;
    }

    public String getName() {
        return name;
    }

    public Path getPath() {
        return path;
    }

    public StringProperty selectedConnectorProperty() {
        return selectedConnector;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public ReadOnlyBooleanProperty dirtyProperty() {
        return dirty;
    }

    @Override
    public void close() {
        database.close();
    }
}
