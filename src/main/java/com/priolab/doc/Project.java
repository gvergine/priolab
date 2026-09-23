package com.priolab.doc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The contents of a {@code .json} project file: which connector and exporter
 * the project uses and the values the user gave their manifest keys.
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "connector": "filebased",
 *   "exporter": null,
 *   "connectorSettings": { "filebased": { "file": "/home/me/db.json" } },
 *   "exporterSettings": {}
 * }
 * }</pre>
 *
 * <p>That is all it holds. The items themselves are <b>not</b> stored here:
 * they are imported from the connector and exported back to it, so the project
 * file only remembers how to reach them. It is written silently — editing any
 * of it never marks the document dirty (see {@link Document}).
 */
public class Project {

    private int version = 1;

    private String connector;
    private String exporter;

    /** {@code connector name -> (manifest key -> value)}. */
    private Map<String, Map<String, String>> connectorSettings = new LinkedHashMap<>();

    /** {@code exporter name -> (manifest key -> value)}. */
    private Map<String, Map<String, String>> exporterSettings = new LinkedHashMap<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getConnector() {
        return connector;
    }

    public void setConnector(String connector) {
        this.connector = connector;
    }

    public String getExporter() {
        return exporter;
    }

    public void setExporter(String exporter) {
        this.exporter = exporter;
    }

    public Map<String, Map<String, String>> getConnectorSettings() {
        return connectorSettings;
    }

    public void setConnectorSettings(Map<String, Map<String, String>> connectorSettings) {
        this.connectorSettings =
                connectorSettings == null ? new LinkedHashMap<>() : connectorSettings;
    }

    public Map<String, Map<String, String>> getExporterSettings() {
        return exporterSettings;
    }

    public void setExporterSettings(Map<String, Map<String, String>> exporterSettings) {
        this.exporterSettings =
                exporterSettings == null ? new LinkedHashMap<>() : exporterSettings;
    }
}
