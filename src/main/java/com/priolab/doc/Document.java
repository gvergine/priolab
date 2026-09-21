package com.priolab.doc;

import com.priolab.db.Database;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

/**
 * An open PrioLab project: the SQLite {@link Database} plus the in-memory,
 * editable state layered on top of it.
 *
 * <p>For now the only editable field is the name of the selected connector,
 * stored in the {@code meta} table under the key {@code connector}. The
 * {@link #dirtyProperty() dirty} flag tracks whether the in-memory value has
 * diverged from what is persisted, so the UI can prompt to save.
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

    private Document(Database database, Path path) {
        this.database = database;
        this.path = path;
        selectedConnector.addListener((obs, oldVal, newVal) ->
                dirty.set(!Objects.equals(newVal, savedConnector)));
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
        dirty.set(false);
    }

    /** Persist the in-memory edits back to the SQLite file. */
    public void save() throws SQLException {
        String value = selectedConnector.get();
        database.putMeta(KEY_CONNECTOR, value);
        savedConnector = value;
        dirty.set(false);
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
