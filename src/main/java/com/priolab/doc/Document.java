package com.priolab.doc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.priolab.model.PluginKind;
import com.priolab.model.PrioItem;
import com.priolab.model.ScoredItem;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An open PrioLab project: the {@code .json} {@link Project} file plus the
 * items currently being prioritized.
 *
 * <p>The two live on very different terms:
 *
 * <ul>
 *   <li>The <b>project file</b> (selected connector / exporter and their
 *       settings) is a convenience. Changing any of it never marks the document
 *       dirty; it is written silently when the document is closed or the app
 *       exits, and read back when it is opened again.</li>
 *   <li>The <b>items</b> are never stored here at all. The connector loads
 *       them and takes them back on a save, so
 *       {@link #dirtyProperty() dirty} means "the items differ from what the
 *       connector last gave us / last took from us" — the only thing that can
 *       actually be lost.</li>
 * </ul>
 */
public final class Document {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private final Project project;

    private final ObservableList<ScoredItem> items = FXCollections.observableArrayList();

    /** Selected connector / exporter name, two-way bound to the top bar. */
    private final Map<PluginKind, StringProperty> selected = new EnumMap<>(PluginKind.class);

    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    /** The items as the connector last loaded or accepted for saving. */
    private List<PrioItem> synced = List.of();

    /** True when a load replaced items the connector has not taken back. */
    private boolean loadOverwrote;

    private Document(Path file, Project project) {
        this.file = file;
        this.project = project;
        for (PluginKind kind : PluginKind.values()) {
            StringProperty property = new SimpleStringProperty(selectedIn(project, kind));
            // A selection is project-file state, not document content: remember
            // it, but never let it touch the dirty flag.
            property.addListener((obs, was, now) -> select(kind, now));
            selected.put(kind, property);
        }
    }

    /**
     * Map a kind onto the project file's fields. {@link Project} is a plain
     * Jackson bean on purpose — giving it {@code PluginKind}-typed helpers made
     * Jackson try to reflect on the enum, which a named module does not export.
     */
    private static String selectedIn(Project project, PluginKind kind) {
        return kind == PluginKind.CONNECTOR ? project.getConnector() : project.getExporter();
    }

    private void select(PluginKind kind, String name) {
        if (kind == PluginKind.CONNECTOR) {
            project.setConnector(name);
        } else {
            project.setExporter(name);
        }
    }

    private Map<String, Map<String, String>> settings(PluginKind kind) {
        return kind == PluginKind.CONNECTOR
                ? project.getConnectorSettings() : project.getExporterSettings();
    }

    /** Start a new project at {@code file}, writing it out right away. */
    public static Document create(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Document document = new Document(file, new Project());
        document.save();
        return document;
    }

    /** Open an existing project file (an absent or empty one starts fresh). */
    public static Document open(Path file) throws IOException {
        Project project = new Project();
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Project read = MAPPER.readValue(reader, Project.class);
                if (read != null) {
                    project = read;
                }
            }
        }
        return new Document(file, project);
    }

    /** Write the project file. Callers do this silently — there is no Save. */
    public void save() throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            MAPPER.writeValue(writer, project);
        }
    }

    public Path getPath() {
        return file;
    }

    /** Split dividers and column widths; captured from the UI before a save. */
    public Layout getLayout() {
        return project.getLayout();
    }

    /** The document's name: the project file's own name. */
    public String getName() {
        return file.getFileName().toString();
    }

    public ObservableList<ScoredItem> getItems() {
        return items;
    }

    /**
     * Replace the items with what a connector load returned.
     *
     * <p>The result is dirty only when the load <em>changed</em> items that were
     * already there: loading into an empty document, or a load that returns
     * exactly what is already shown, leaves the document clean.
     */
    public void loadItems(List<PrioItem> loaded) {
        boolean wasEmpty = items.isEmpty();
        List<PrioItem> before = snapshot();

        List<ScoredItem> scored = new ArrayList<>(loaded.size());
        int order = 1;
        for (PrioItem item : loaded) {
            ScoredItem si = new ScoredItem(item, order++);
            // Every later score edit is the user's, and counts towards dirty.
            si.businessValueProperty().addListener((obs, was, now) -> recomputeDirty());
            si.timeCriticalityProperty().addListener((obs, was, now) -> recomputeDirty());
            si.riskReductionProperty().addListener((obs, was, now) -> recomputeDirty());
            si.jobSizeProperty().addListener((obs, was, now) -> recomputeDirty());
            scored.add(si);
        }
        items.setAll(scored);

        synced = snapshot();
        loadOverwrote = !wasEmpty && !synced.equals(before);
        recomputeDirty();
    }

    /** The connector took the items: what is on screen is now the stored truth. */
    public void markSaved() {
        loadOverwrote = false;
        synced = snapshot();
        recomputeDirty();
    }

    /** The items with their current scores, in the order they were loaded. */
    public List<PrioItem> snapshot() {
        return items.stream().map(ScoredItem::snapshot).toList();
    }

    private void recomputeDirty() {
        dirty.set(loadOverwrote || !snapshot().equals(synced));
    }

    /** The selected connector / exporter name, two-way bound to the top bar. */
    public StringProperty selectedProperty(PluginKind kind) {
        return selected.get(kind);
    }

    /**
     * The value assigned to {@code key} of the connector / exporter
     * {@code name} ({@code ""} if unset). Project-file state: reading or
     * writing it never affects {@link #dirtyProperty() dirty}.
     */
    public String getSetting(PluginKind kind, String name, String key) {
        Map<String, String> forName = settings(kind).get(name);
        String value = forName == null ? null : forName.get(key);
        return value == null ? "" : value;
    }

    /** Assign a value to {@code key} of {@code name}; blank means unset. */
    public void setSetting(PluginKind kind, String name, String key, String value) {
        Map<String, Map<String, String>> settings = settings(kind);
        if (value == null || value.isEmpty()) {
            Map<String, String> forName = settings.get(name);
            if (forName != null) {
                forName.remove(key);
                if (forName.isEmpty()) {
                    settings.remove(name);
                }
            }
        } else {
            settings.computeIfAbsent(name, n -> new HashMap<>()).put(key, value);
        }
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public ReadOnlyBooleanProperty dirtyProperty() {
        return dirty;
    }
}
