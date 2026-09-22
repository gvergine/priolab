package com.priolab.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * connector directory as the working directory and the per-project setting
 * values as environment variables. There is no handshake: a connector
 * (importer) simply prints its result on stdout, while an exporter is fed the
 * prioritized items on stdin. stdout and stderr are streamed separately, line
 * by line, because stdout carries the result while stderr is only diagnostics
 * for the console pane. This class deals with process lifecycle and streaming,
 * nothing else — it is the same for both kinds of program.
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
     * Launch the connector's command (relative to its directory) with
     * {@code env} added to the inherited environment, and stream its output line
     * by line: stdout to {@code onLine} (the result PrioLab parses) and stderr
     * to {@code onErrorLine} (diagnostics). Reading happens on daemon threads,
     * so the callbacks are invoked off the FX thread — UI callers must marshal
     * to the FX thread themselves.
     *
     * <p>The child's stdin is closed right away, so a connector that reads it
     * sees end-of-file instead of hanging.
     *
     * @param env         extra environment variables for the child
     * @param onLine      consumes each stdout line (never {@code null})
     * @param onErrorLine consumes each stderr line (never {@code null})
     * @param onExit      consumes the process exit code when it finishes (nullable)
     * @throws IOException           if the process cannot be started
     * @throws IllegalStateException if this connector is already running
     */
    public synchronized void run(Map<String, String> env,
                                 Consumer<String> onLine,
                                 Consumer<String> onErrorLine,
                                 IntConsumer onExit) throws IOException {
        run(env, null, onLine, onErrorLine, onExit);
    }

    /**
     * As {@link #run(Map, Consumer, Consumer, IntConsumer)}, but first write
     * {@code input} to the child's stdin, one line each, and close it. The
     * writing happens on its own daemon thread, so a child that only reads part
     * of its input (or none) cannot block PrioLab.
     *
     * @param input the lines to feed to stdin, or {@code null} to close it right
     *              away (what a connector run does)
     */
    public synchronized void run(Map<String, String> env,
                                 List<String> input,
                                 Consumer<String> onLine,
                                 Consumer<String> onErrorLine,
                                 IntConsumer onExit) throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("Connector already running: " + getName());
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommand());
        pb.directory(directory.toFile());
        pb.environment().putAll(env);
        process = pb.start();

        Process started = process;
        if (input == null) {
            closeQuietly(started.getOutputStream());
        } else {
            Thread writer = new Thread(() -> writeLines(started, input, onErrorLine),
                    "connector-" + getName() + "-stdin");
            writer.setDaemon(true);
            writer.start();
        }

        Thread stderrReader = new Thread(
                () -> readLines(started.getErrorStream(), onErrorLine),
                "connector-" + getName() + "-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        Thread reader = new Thread(() -> pump(started, onLine, stderrReader, onExit),
                "connector-" + getName());
        reader.setDaemon(true);
        reader.start();
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

    private void pump(Process p, Consumer<String> onLine, Thread stderrReader, IntConsumer onExit) {
        readLines(p.getInputStream(), onLine);
        try {
            // Let the last diagnostics land before we report the exit code.
            stderrReader.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /**
     * Write every line of {@code input} to the child's stdin and close it, so
     * the program sees end-of-file and can finish. A child that exits early
     * leaves us with a broken pipe; that is normal, not an error worth raising.
     */
    private static void writeLines(Process p, List<String> input, Consumer<String> onErrorLine) {
        try (Writer out = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String line : input) {
                out.write(line);
                out.write('\n');
            }
        } catch (IOException e) {
            onErrorLine.accept("[stopped writing to stdin: " + e.getMessage() + "]");
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The child may already be gone; its stdin is of no use to us.
        }
    }

    /** Feed every line of {@code stream} to {@code consumer} until end of file. */
    private static void readLines(InputStream stream, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        } catch (IOException e) {
            consumer.accept("[error reading output: " + e.getMessage() + "]");
        }
    }

    @Override
    public String toString() {
        return getName() + " (" + directory + ")";
    }
}
