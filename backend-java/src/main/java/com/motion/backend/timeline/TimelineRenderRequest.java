package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * application {@code renderTimelineRequest}. When both fields are present {@code projectId} wins.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimelineRenderRequest(String projectId, TimelineProject project) {
}
