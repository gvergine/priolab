package com.priolab.connector;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code manifest.json} that lives inside a connector directory and declares
 * the connector. A connector directory is only valid if it contains a manifest
 * whose {@link #name} equals the directory name.
 *
 * <pre>{@code
 * {
 *   "name": "jira",
 *   "version": "1.0.0",
 *   "author": "Jane Doe",
 *   "description": "Pulls issues from Jira.",
 *   "command": "./run.sh",
 *   "keys": ["baseUrl", "project", "apiToken"]
 * }
 * }</pre>
 *
 * {@link #command} is the command PrioLab runs, resolved relative to the
 * connector directory. The {@link #keys} are the names of settings the user
 * configures per project; their values are stored in a table of the project's
 * SQLite file.
 */
public class ConnectorManifest {

    private String name;
    private String version;
    private String author;
    private String description;
    private String command;
    private List<String> keys = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys == null ? new ArrayList<>() : keys;
    }
}
