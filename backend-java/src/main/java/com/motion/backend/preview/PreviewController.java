package com.motion.backend.preview;

import com.motion.backend.auth.ApiException;
import com.motion.backend.auth.RequestAuth;
import com.motion.backend.media.FileMediaSource;
import com.motion.backend.media.MediaFileServer;
import com.motion.backend.media.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Thin wire boundary for thumbnail and waveform previews. */
@RestController
public class PreviewController {

    private final PreviewService service;
    private final MediaFileServer files;

    public PreviewController(PreviewService service, MediaFileServer files) {
        this.service = service;
        this.files = files;
    }

    @GetMapping("/api/videos/{id}/thumbnails")
    public ThumbnailResponse thumbnails(
            @PathVariable String id,
            @RequestParam(name = "count", required = false) String count,
            HttpServletRequest request) {
        String userId = RequestAuth.require(request).user().id();
        PreviewService.ThumbnailPreview preview = service.ensureThumbnailStrip(userId, id, parseInteger(count));
        ThumbnailStrip strip = preview.strip();
        return new ThumbnailResponse(
                strip.count(), strip.tileWidth(), strip.tileHeight(), strip.width(), strip.height(),
                preview.duration(), "/api/videos/" + id + "/thumbnails/sprite?count=" + strip.count());
    }

    @GetMapping("/api/videos/{id}/thumbnails/sprite")
    public ResponseEntity<StreamingResponseBody> sprite(
            @PathVariable String id,
            @RequestParam(name = "count", required = false) String count,
            HttpServletRequest request) {
        String userId = RequestAuth.require(request).user().id();
        PreviewService.ThumbnailPreview preview = service.ensureThumbnailStrip(userId, id, parseInteger(count));
        Path path = service.spritePath(id, preview.strip());
        try {
            long size = Files.size(path);
            MediaService.ServableFile file = new MediaService.ServableFile(
                    "thumbnails.jpg", size, FileMediaSource.open(path));
            return files.serve(request, file, false, MediaFileServer.ServeOptions.sprite());
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(404, "thumbnails are not available");
        }
    }

    @GetMapping("/api/videos/{id}/waveform")
    public WaveformResponse waveform(
            @PathVariable String id,
            @RequestParam(name = "buckets", required = false) String buckets,
            HttpServletRequest request) {
        String userId = RequestAuth.require(request).user().id();
        return service.ensureWaveform(userId, id, parseInteger(buckets));
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
