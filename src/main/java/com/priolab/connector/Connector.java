package com.priolab.connector;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A connector is an external program that PrioLab launches as a child process
 * and drives over a simple line-delimited protocol:
 *
 * <ul>
 *   <li>PrioLab writes exactly one request (a single line, typically JSON) to
 *       the connector's stdin.</li>
 *   <li>The connector replies with exactly one response line on its stdout.</li>
 * </ul>
 *
 * The set of logical commands (initialize, list tasks, save prioritization, …)
 * is defined in {@link Protocol}. This class only deals with process lifecycle
 * and framing; it does not interpret the payloads.
 */
public class Connector implements AutoCloseable {

    private final String name;
    private final Path executable;

    private Process process;
    private BufferedWriter toProcess;
    private BufferedReader fromProcess;

    public Connector(String name, Path executable) {
        this.name = name;
        this.executable = executable;
    }

    public String getName() {
        return name;
    }

    public Path getExecutable() {
        return executable;
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** Launch the connector process if it is not already running. */
    public synchronized void start() throws IOException {
        if (isRunning()) {
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(executable.toString());
        // Keep the connector's stderr separate so diagnostics don't corrupt the
        // response stream; inherit it so it surfaces in PrioLab's own logs.
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        toProcess = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        fromProcess = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Send one request line and block until the connector returns one response
     * line. Starts the process on demand.
     *
     * @return the response line, or {@code null} if the connector closed its
     *     stdout without replying.
     */
    public synchronized String send(String requestLine) throws IOException {
        if (!isRunning()) {
            start();
        }
        toProcess.write(requestLine);
        toProcess.newLine();
        toProcess.flush();
        return fromProcess.readLine();
    }

    /** Terminate the connector process, gracefully if possible. */
    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        try {
            if (toProcess != null) {
                toProcess.close();
            }
        } catch (IOException ignored) {
            // best effort
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

    @Override
    public String toString() {
        return name + " (" + executable + ")";
    }
}
