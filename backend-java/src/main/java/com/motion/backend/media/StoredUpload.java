package com.motion.backend.media;

import java.nio.file.Path;

/** Outcome of placing an upload into final storage. */
public record StoredUpload(Path path, boolean encrypted, String wrappedKey) {
}
