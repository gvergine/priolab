package com.priolab.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A connector is a directory under the configured connectors path that contains
 * a {@code manifest.json} (see {@link ConnectorManifest}). Its {@link #getName()
 * name} is the directory name.
 *
 * <p>PrioLab runs the connector by launching the manifest's
 * {@link ConnectorManifest#getCommand() command} as a child process, with the
 * connector directory as the working directory. The child's stdout and stderr
 * are merged and streamed line by line so the GUI can show them in a console
 * pane. This class only deals with process lifecycle and streaming.
 */
public class Connector implements AutoCloseable {

    private final Path directory;
    private final ConnectorManifest manifest;

    private Process process;

    public Connector(Path directory, ConnectorManifest manifest) {
        this.directory = directory;
        this.manifest = manifest;
    }

    /** The connector name (equals the directory name and the manifest name). */
    public String getName() {
        return manifest.getName();
    }

    public Path getDirectory() {
        return directory;
    }

    public ConnectorManifest getManifest() {
        return manifest;
    }

    /** The setting keys this connector expects the user to configure. */
    public List<String> getKeys() {
        return manifest.getKeys();
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Launch the connector's command (relative to its directory) and stream the
     * merged stdout+stderr to {@code onLine}, one call per line. Reading happens
     * on a daemon thread, so {@code onLine} and {@code onExit} are invoked off
     * the FX thread — UI callers must marshal to the FX thread themselves.
     *
     * @param onLine consumes each output line (never {@code null})
     * @param onExit consumes the process exit code when it finishes (nullable)
     * @throws IOException           if the process cannot be started
     * @throws IllegalStateException if this connector is already running
     */
    public synchronized void run(Consumer<String> onLine, IntConsumer onExit) throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("Connector already running: " + getName());
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommand());
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true); // merge stderr into stdout
        process = pb.start();

        Process started = process;
        Thread reader = new Thread(() -> pump(started, onLine, onExit),
                "connector-" + getName());
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Write a single line (a trailing newline is appended) to the running
     * connector's stdin and flush it. Used to drive the line-oriented protocol
     * (see {@link Protocol}).
     *
     * @throws IOException if no process is running or the write fails
     */
    public synchronized void send(String line) throws IOException {
        Process p = process;
        if (p == null || !p.isAlive()) {
            throw new IOException("Connector is not running: " + getName());
        }
        OutputStream stdin = p.getOutputStream();
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Terminate the connector process, gracefully if possible. */
    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    /**
     * Split the manifest command into argv. The first token (the program) is
     * resolved against the connector directory when it is a relative path,
     * because Java resolves a relative executable against the JVM's working
     * directory rather than {@link ProcessBuilder#directory}.
     */
    private List<String> buildCommand() throws IOException {
        String command = manifest.getCommand();
        if (command == null || command.isBlank()) {
            throw new IOException("Connector '" + getName() + "' has no command");
        }
        String[] tokens = command.trim().split("\\s+");
        List<String> argv = new ArrayList<>(tokens.length);
        String program = tokens[0];
        Path programPath = Path.of(program);
        // A relative path (e.g. "./run.sh" or "bin/run") points inside the dir.
        if (!programPath.isAbsolute() && program.indexOf('/') >= 0) {
            argv.add(directory.resolve(programPath).normalize().toString());
        } else {
            argv.add(program);
        }
        for (int i = 1; i < tokens.length; i++) {
            argv.add(tokens[i]);
        }
        return argv;
    }

    private void pump(Process p, Consumer<String> onLine, IntConsumer onExit) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException e) {
            onLine.accept("[error reading output: " + e.getMessage() + "]");
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        synchronized (this) {
            if (process == p) {
                process = null;
            }
        }
        if (onExit != null) {
            onExit.accept(code);
        }
    }

    @Override
    public String toString() {
        return getName() + " (" + directory + ")";
    }
}
