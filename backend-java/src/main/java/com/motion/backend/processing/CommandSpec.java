package com.motion.backend.processing;

import java.util.List;

/** An argv-only FFmpeg command plus progress instructions for the executor. */
public record CommandSpec(List<String> arguments, boolean progressEnabled, double durationSeconds) {

    public CommandSpec {
        arguments = List.copyOf(arguments);
    }
}
