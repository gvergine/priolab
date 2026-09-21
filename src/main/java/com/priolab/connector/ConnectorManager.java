package com.priolab.connector;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers connectors under the configured connectors path (see
 * {@code config.json}).
 *
 * <p>A connector is a <em>direct subdirectory</em> whose name is the connector
 * name. It is only valid if it contains a {@code manifest.json} (see
 * {@link ConnectorManifest}) whose {@code name} equals the directory name and
 * that declares {@code version}, {@code author}, {@code description} and
 * {@code command}. Directories that fail these checks are skipped (a note is
 * written to stderr).
 */
public class ConnectorManager {

    private static final String MANIFEST_FILE = "manifest.json";

    private final Path connectorsDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ConnectorManager(Path connectorsDir) {
        this.connectorsDir = connectorsDir;
    }

    public Path getConnectorsDir() {
        return connectorsDir;
    }

    /**
     * List the valid connectors found in the connectors directory. The connector
     * processes are not started here; that happens lazily on the first
     * {@link Connector#send} call.
     */
    public List<Connector> discover() throws IOException {
        List<Connector> connectors = new ArrayList<>();
        if (connectorsDir == null || !Files.isDirectory(connectorsDir)) {
            return connectors;
        }
        try (Stream<Path> entries = Files.list(connectorsDir)) {
            entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(dir -> {
                        Connector connector = tryLoad(dir);
                        if (connector != null) {
                            connectors.add(connector);
                        }
                    });
        }
        return connectors;
    }

    /** Load a connector from a directory, or return {@code null} if invalid. */
    private Connector tryLoad(Path dir) {
        String dirName = dir.getFileName().toString();
        Path manifestFile = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return null; // not a connector, no manifest
        }
        try {
            ConnectorManifest manifest =
                    mapper.readValue(manifestFile.toFile(), ConnectorManifest.class);
            String problem = validate(manifest, dirName);
            if (problem != null) {
                System.err.println("Ignoring connector '" + dirName + "': " + problem);
                return null;
            }
            return new Connector(dir, manifest);
        } catch (IOException e) {
            System.err.println("Ignoring connector '" + dirName
                    + "': could not read " + MANIFEST_FILE + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** @return a human-readable reason the manifest is invalid, or {@code null}. */
    private static String validate(ConnectorManifest m, String dirName) {
        if (!dirName.equals(m.getName())) {
            return "manifest name '" + m.getName() + "' must match the directory name";
        }
        if (isBlank(m.getVersion())) {
            return "manifest is missing 'version'";
        }
        if (isBlank(m.getAuthor())) {
            return "manifest is missing 'author'";
        }
        if (isBlank(m.getDescription())) {
            return "manifest is missing 'description'";
        }
        if (isBlank(m.getCommand())) {
            return "manifest is missing 'command'";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
