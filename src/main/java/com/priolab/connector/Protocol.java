package com.priolab.connector;

/**
 * Logical command names for the connector protocol. A request is a single JSON
 * line of the form:
 *
 * <pre>{@code {"command": "list_tasks", "args": { ... }}}</pre>
 *
 * and a response is a single JSON line of the form:
 *
 * <pre>{@code {"ok": true, "data": { ... }}}</pre>
 *
 * These constants are the source of truth for the command vocabulary shared
 * between PrioLab and connector implementations.
 */
public final class Protocol {

    private Protocol() {
    }

    /** Handshake / capability negotiation performed once after launch. */
    public static final String INITIALIZE = "initialize";

    /** Fetch the list of items to prioritize. */
    public static final String LIST_TASKS = "list_tasks";

    /** Persist a prioritization produced by the user. */
    public static final String SAVE = "save";

    /** Graceful shutdown request before the process is terminated. */
    public static final String SHUTDOWN = "shutdown";
}
