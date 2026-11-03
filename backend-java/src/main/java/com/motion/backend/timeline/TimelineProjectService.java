package com.motion.backend.timeline;

import com.motion.backend.auth.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;

/** application timeline-project business rules: lenient saves, strict revision handling. */
@Component
public class TimelineProjectService {

    private final TimelineProjectStore store;

    public TimelineProjectService(TimelineProjectStore store) {
        this.store = store;
    }

    public TimelineProject create(String userId, TimelineProject project) {
        TimelineProject normalized = TimelineNormalizer.normalize(project);
        requireNoFatalErrors(normalized);
        try {
            return store.create(userId, normalized);
        } catch (RuntimeException exception) {
            throw new ApiException(500, "failed to save timeline");
        }
    }

    public List<TimelineProject> list(String userId) {
        try {
            return store.list(userId);
        } catch (RuntimeException exception) {
            throw new ApiException(500, "failed to load timelines");
        }
    }

    public TimelineProject get(String userId, String id) {
        try {
            return store.get(userId, id)
                    .orElseThrow(() -> new ApiException(404, "timeline not found"));
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(500, "failed to load timeline");
        }
    }

    public TimelineProject update(String userId, String id, TimelineProject project) {
        TimelineProject normalized = TimelineNormalizer.normalize(project);
        requireNoFatalErrors(normalized);
        TimelineProject targeted = normalized.withMetadata(id, normalized.revision(), null, null);
        try {
            return store.update(userId, targeted);
        } catch (TimelineRevisionConflictException exception) {
            throw new ApiException(409,
                    "this timeline was changed in another session; reload it before saving");
        } catch (TimelineProjectNotFoundException exception) {
            throw new ApiException(404, "timeline not found");
        } catch (RuntimeException exception) {
            throw new ApiException(500, "failed to save timeline");
        }
    }

    public void delete(String userId, String id) {
        try {
            store.delete(userId, id);
        } catch (TimelineProjectNotFoundException exception) {
            throw new ApiException(404, "timeline not found");
        } catch (RuntimeException exception) {
            throw new ApiException(500, "failed to delete timeline");
        }
    }

    private static void requireNoFatalErrors(TimelineProject project) {
        List<TimelineValidationError> fatal = TimelineValidator.validateStructure(project).stream()
                .filter(error -> TimelineValidator.isFatalSaveCode(error.code()))
                .toList();
        if (!fatal.isEmpty()) {
            throw new TimelineValidationException(400, fatal);
        }
    }
}
