package com.priolab.connector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers and owns the connector executables found in the configured
 * connectors directory (see {@code config.json}).
 */
public class ConnectorManager {

    private final Path connectorsDir;

    public ConnectorManager(Path connectorsDir) {
        this.connectorsDir = connectorsDir;
    }

    public Path getConnectorsDir() {
        return connectorsDir;
    }

    /**
     * List the executable files in the connectors directory as {@link Connector}
     * instances. The connectors are not started here; that happens lazily on the
     * first {@link Connector#send} call.
     */
    public List<Connector> discover() throws IOException {
        List<Connector> connectors = new ArrayList<>();
        if (connectorsDir == null || !Files.isDirectory(connectorsDir)) {
            return connectors;
        }
        try (Stream<Path> entries = Files.list(connectorsDir)) {
            entries.filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> connectors.add(new Connector(stripExtension(p), p)));
        }
        return connectors;
    }

    private static String stripExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
