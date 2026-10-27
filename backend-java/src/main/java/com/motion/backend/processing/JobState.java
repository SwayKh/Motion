package com.motion.backend.processing;

import java.util.Locale;

/**
 * The five explicit internal job states.
 *
 * <p>The API also exposes {@code cancel_requested} on the wire. It is modeled here as {@link #RUNNING}
 * with a persisted cancellation request, so the core state machine stays explicit while the
 * database and HTTP contract remain stable.</p>
 */
public enum JobState {
    QUEUED("queued"),
    RUNNING("processing"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wireValue;

    JobState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public static boolean isTerminal(String status) {
        JobState state = fromWire(status);
        return state != null && state.terminal();
    }

    public static boolean isActive(String status) {
        JobState state = fromWire(status);
        return state == QUEUED || state == RUNNING;
    }

    public static JobState fromWire(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "queued" -> QUEUED;
            case "processing", "cancel_requested" -> RUNNING;
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            case "cancelled" -> CANCELLED;
            default -> null;
        };
    }

    /**
     * Valid transitions between core states. {@code cancel_requested} is a substate of RUNNING and
     * therefore uses the same RUNNING → CANCELLED transition.
     */
    public static boolean allows(JobState from, JobState to) {
        if (from == null || to == null || from.terminal()) {
            return false;
        }
        return switch (from) {
            case QUEUED -> to == RUNNING || to == CANCELLED;
            case RUNNING -> to == QUEUED || to == COMPLETED || to == FAILED || to == CANCELLED;
            default -> false;
        };
    }
}
