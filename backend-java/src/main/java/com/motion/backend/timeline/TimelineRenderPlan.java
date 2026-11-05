package com.motion.backend.timeline;

import com.motion.backend.processing.ProcessingRequest;

/** Immutable decoded render snapshot: the only input the async worker is allowed to see. */
public record TimelineRenderPlan(
        String primaryVideoId,
        ProcessingRequest request,
        double duration) {
}
