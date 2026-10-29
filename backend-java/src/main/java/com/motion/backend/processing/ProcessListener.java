package com.motion.backend.processing;

/** Observes an FFmpeg process so async cancellation can terminate it directly. */
@FunctionalInterface
public interface ProcessListener {

    ProcessListener NOOP = new ProcessListener() {
        @Override
        public void started(Process process) {
        }

        @Override
        public void finished(Process process) {
        }
    };

    void started(Process process);

    default void finished(Process process) {
    }
}
