package com.priolab.doc;

import com.priolab.db.Database;
import com.priolab.model.ItemScore;
import com.priolab.model.PluginKind;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An open PrioLab project: the SQLite {@link Database} plus the in-memory,
 * editable state layered on top of it.
 *
 * <p>The editable fields are the names of the selected connector and exporter
 * (stored in the {@code meta} table under the keys {@code connector} and
 * {@code exporter}), the setting values the user assigns to their declared keys
 * (stored per {@link PluginKind} in the {@code connector_settings} /
 * {@code exporter_settings} tables) and the per-item WSJF scores keyed by item
 * id (stored in the {@code item_scores} table). The {@link #dirtyProperty() dirty}
 * flag tracks whether any in-memory value has diverged from what is persisted,
 * so the UI can prompt to save.
 */
public class Document implements AutoCloseable {

    private final Database database;
    private final Path path;
    private String name;

    /** Selected plugin name per kind, each stored under its own {@code meta} key. */
    private final Map<PluginKind, StringProperty> selected = new EnumMap<>(PluginKind.class);

    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    /** The last selection written to (or read from) disk, for dirty comparison. */
    private final Map<PluginKind, String> savedSelection = new EnumMap<>(PluginKind.class);

    /**
     * Setting values per kind, {@code name -> (key -> value)}. Empty values are
     * normalised away (absent), so the two maps compare cleanly for the dirty
     * check. {@code savedSettings} mirrors what is on disk; {@code editSettings}
     * holds the live in-memory edits.
     */
    private final Map<PluginKind, Map<String, Map<String, String>>> savedSettings =
            new EnumMap<>(PluginKind.class);
    private final Map<PluginKind, Map<String, Map<String, String>>> editSettings =
            new EnumMap<>(PluginKind.class);

    /**
     * Per-item WSJF scores, {@code item id -> score}. Scores that carry nothing
     * ({@link ItemScore#isEmpty()}) are normalised away, so the two maps compare
     * cleanly for the dirty check. Holds every score in the project, not just
     * the items the last connector run returned, so scores for items that are
     * temporarily absent survive.
     */
    private Map<String, ItemScore> savedScores = new HashMap<>();
    private Map<String, ItemScore> editScores = new HashMap<>();

    private Document(Database database, Path path) {
        this.database = database;
        this.path = path;
        for (PluginKind kind : PluginKind.values()) {
            StringProperty property = new SimpleStringProperty();
            property.addListener((obs, oldVal, newVal) -> recomputeDirty());
            selected.put(kind, property);
            savedSettings.put(kind, new HashMap<>());
            editSettings.put(kind, new HashMap<>());
        }
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
        for (PluginKind kind : PluginKind.values()) {
            String stored = database.getMeta(kind.label());
            savedSelection.put(kind, stored);
            selected.get(kind).set(stored);
            savedSettings.put(kind, database.getAllSettings(kind));
            editSettings.put(kind, deepCopy(savedSettings.get(kind)));
        }
        this.savedScores = database.getAllItemScores();
        this.editScores = new HashMap<>(savedScores);
        dirty.set(false);
    }

    /** Persist the in-memory edits back to the SQLite file. */
    public void save() throws SQLException {
        for (PluginKind kind : PluginKind.values()) {
            String value = selected.get(kind).get();
            database.putMeta(kind.label(), value);
            savedSelection.put(kind, value);

            // Upsert every current setting; delete any that were removed.
            Map<String, Map<String, String>> edits = editSettings.get(kind);
            for (var nameEntry : edits.entrySet()) {
                for (var keyEntry : nameEntry.getValue().entrySet()) {
                    database.putSetting(
                            kind, nameEntry.getKey(), keyEntry.getKey(), keyEntry.getValue());
                }
            }
            for (var nameEntry : savedSettings.get(kind).entrySet()) {
                Map<String, String> current =
                        edits.getOrDefault(nameEntry.getKey(), Map.of());
                for (String key : nameEntry.getValue().keySet()) {
                    if (!current.containsKey(key)) {
                        database.deleteSetting(kind, nameEntry.getKey(), key);
                    }
                }
            }
            savedSettings.put(kind, deepCopy(edits));
        }

        // Same upsert-then-delete pass for the per-item scores.
        for (var entry : editScores.entrySet()) {
            database.putItemScore(entry.getKey(), entry.getValue());
        }
        for (String id : savedScores.keySet()) {
            if (!editScores.containsKey(id)) {
                database.deleteItemScore(id);
            }
        }
        savedScores = new HashMap<>(editScores);

        dirty.set(false);
    }

    /** The stored score for {@code itemId}, or {@code null} if it has none. */
    public ItemScore getItemScore(String itemId) {
        return editScores.get(itemId);
    }

    /**
     * Record the WSJF scores for {@code itemId}. A score with nothing set is
     * treated as no score at all. Updates the {@link #dirtyProperty() dirty} flag.
     */
    public void setItemScore(String itemId, ItemScore score) {
        if (score == null || score.isEmpty()) {
            editScores.remove(itemId);
        } else {
            editScores.put(itemId, score);
        }
        recomputeDirty();
    }

    /**
     * The in-memory value assigned to {@code key} of the connector / exporter
     * {@code name} ({@code ""} if unset).
     */
    public String getSetting(PluginKind kind, String name, String key) {
        Map<String, String> forName = editSettings.get(kind).get(name);
        String value = forName == null ? null : forName.get(key);
        return value == null ? "" : value;
    }

    /**
     * Assign a value to {@code key} of the connector / exporter {@code name}.
     * Blank values are treated as unset. Updates the
     * {@link #dirtyProperty() dirty} flag.
     */
    public void setSetting(PluginKind kind, String name, String key, String value) {
        Map<String, Map<String, String>> edits = editSettings.get(kind);
        if (value == null || value.isEmpty()) {
            Map<String, String> forName = edits.get(name);
            if (forName != null) {
                forName.remove(key);
                if (forName.isEmpty()) {
                    edits.remove(name);
                }
            }
        } else {
            edits.computeIfAbsent(name, n -> new HashMap<>()).put(key, value);
        }
        recomputeDirty();
    }

    private void recomputeDirty() {
        boolean changed = !editScores.equals(savedScores);
        for (PluginKind kind : PluginKind.values()) {
            changed |= !Objects.equals(selected.get(kind).get(), savedSelection.get(kind))
                    || !editSettings.get(kind).equals(savedSettings.get(kind));
        }
        dirty.set(changed);
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

    /** The selected connector / exporter name, two-way bound to the top bar. */
    public StringProperty selectedProperty(PluginKind kind) {
        return selected.get(kind);
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
