package com.motion.backend.processing;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory registry of the one live execution for each claimed job.
 *
 * <p>The database remains the queue and source of truth. This registry exists only so a POST
 * cancel can interrupt the exact worker thread and terminate the exact FFmpeg process (including
 * descendants). A cancel that arrives before the worker registers is remembered by lease token and
 * consumed immediately at registration, matching the pending-cancel map.</p>
 */
@Component
public class JobExecutionRegistry {

    private final Map<String, ExecutionHandle> active = new ConcurrentHashMap<>();
    private final Map<String, String> pendingCancels = new ConcurrentHashMap<>();

    /**
     * Registers the current worker. If a different attempt is already registered, that handle is
     * returned so the caller can refuse to run a duplicate execution.
     */
    public ExecutionHandle register(String jobId, String token, String workerId, Thread thread) {
        ExecutionHandle handle = new ExecutionHandle(jobId, token, workerId, thread);
        ExecutionHandle existing = active.putIfAbsent(jobId, handle);
        if (existing != null) {
            return existing;
        }
        String pendingToken = pendingCancels.remove(jobId);
        if (token.equals(pendingToken)) {
            handle.stop(StopReason.USER_CANCEL);
        }
        return handle;
    }

    /** Records a user cancellation and stops an already-registered attempt. */
    public void requestCancel(String jobId, String token) {
        pendingCancels.put(jobId, token);
        ExecutionHandle handle = active.get(jobId);
        if (handle != null && handle.matches(token)) {
            handle.stop(StopReason.USER_CANCEL);
        }
    }

    /** Interrupts every active worker without changing its database status to cancel_requested. */
    public void stopForShutdown() {
        for (ExecutionHandle handle : active.values()) {
            pendingCancels.put(handle.jobId(), handle.token());
            handle.stop(StopReason.SHUTDOWN);
        }
    }

    public boolean stopped(String jobId, String token) {
        ExecutionHandle handle = active.get(jobId);
        return handle != null && handle.matches(token) && handle.stopped();
    }

    public StopReason reason(String jobId, String token) {
        ExecutionHandle handle = active.get(jobId);
        return handle != null && handle.matches(token) ? handle.reason() : null;
    }

    public void attachProcess(String jobId, String token, Process process) {
        ExecutionHandle handle = active.get(jobId);
        if (handle == null || !handle.matches(token) || process == null) {
            return;
        }
        handle.processes().add(process);
        if (handle.stopped()) {
            FFmpegExecutor.terminate(process);
        }
    }

    public void detachProcess(String jobId, String token, Process process) {
        ExecutionHandle handle = active.get(jobId);
        if (handle != null && handle.matches(token) && process != null) {
            handle.processes().remove(process);
        }
    }

    public void unregister(String jobId, String token) {
        ExecutionHandle handle = active.get(jobId);
        if (handle != null && handle.matches(token)) {
            active.remove(jobId, handle);
            pendingCancels.remove(jobId, token);
        }
    }

    public Collection<ExecutionHandle> activeExecutions() {
        return active.values();
    }

    public enum StopReason {
        USER_CANCEL,
        SHUTDOWN
    }

    /** One attempt's cancellation state and attached FFmpeg processes. */
    public static final class ExecutionHandle {
        private final String jobId;
        private final String token;
        private final String workerId;
        private final Thread thread;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final CopyOnWriteArrayList<Process> processes = new CopyOnWriteArrayList<>();
        private volatile StopReason reason;

        private ExecutionHandle(String jobId, String token, String workerId, Thread thread) {
            this.jobId = jobId;
            this.token = token;
            this.workerId = workerId;
            this.thread = thread;
        }

        public String jobId() {
            return jobId;
        }

        public String token() {
            return token;
        }

        public String workerId() {
            return workerId;
        }

        public boolean matches(String candidate) {
            return token.equals(candidate);
        }

        public boolean stopped() {
            return stopped.get();
        }

        public StopReason reason() {
            return reason;
        }

        public CopyOnWriteArrayList<Process> processes() {
            return processes;
        }

        /** First stop reason wins, except that an explicit user cancel upgrades a shutdown stop. */
        public boolean stop(StopReason requested) {
            boolean first = stopped.compareAndSet(false, true);
            if (first || requested == StopReason.USER_CANCEL) {
                reason = requested;
            }
            for (Process process : processes) {
                FFmpegExecutor.terminate(process);
            }
            thread.interrupt();
            return first;
        }
    }
}
