package com.motion.backend.media;

import com.motion.backend.auth.ApiException;
import com.motion.backend.auth.RequestAuth;
import com.motion.backend.rate.RateLimitRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** Media lifecycle routes: listing, upload, metadata, streaming, download, and deletion. */
@RestController
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final MediaService media;
    private final UploadService uploads;
    private final MediaFileServer files;
    private final RateLimitRegistry rateLimits;

    public MediaController(MediaService media, UploadService uploads, MediaFileServer files,
                           RateLimitRegistry rateLimits) {
        this.media = media;
        this.uploads = uploads;
        this.files = files;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/api/videos")
    public ResponseEntity<List<VideoResponse>> list(HttpServletRequest request) {
        return ResponseEntity.ok(media.list(userId(request)));
    }

    @PostMapping("/api/upload")
    public ResponseEntity<VideoResponse> upload(
            @RequestParam(value = "video", required = false) MultipartFile video,
            HttpServletRequest request) {
        if (!rateLimits.allowUpload(request)) {
            throw new ApiException(429, "too many uploads; try again later");
        }
        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
            throw new UploadClientException("invalid multipart upload");
        }
        if (video == null) {
            throw new UploadClientException("missing or invalid 'video' file field");
        }
        try {
            MediaRecord uploaded = uploads.upload(video, userId(request));
            VideoResponse response = media.metadata(userId(request), uploaded.id());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (UploadClientException exception) {
            throw exception;
        } catch (UploadInternalException exception) {
            log.error("upload failed: {}", exception.getMessage(), exception);
            throw new ApiException(500, "failed to process upload");
        }
    }

    @GetMapping("/api/videos/{id}")
    public ResponseEntity<VideoResponse> metadata(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(media.metadata(userId(request), id));
    }

    @GetMapping("/api/videos/{id}/original")
    public ResponseEntity<StreamingResponseBody> original(@PathVariable String id, HttpServletRequest request)
            throws Exception {
        MediaService.ServableFile file = media.original(userId(request), id);
        return files.serve(request, file, false);
    }

    @GetMapping("/api/videos/{id}/output")
    public ResponseEntity<StreamingResponseBody> output(@PathVariable String id, HttpServletRequest request)
            throws Exception {
        MediaService.ServableFile file = media.output(userId(request), id);
        return files.serve(request, file, false);
    }

    @GetMapping("/api/videos/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id, HttpServletRequest request)
            throws Exception {
        MediaService.ServableFile file = media.output(userId(request), id);
        return files.serve(request, file, true);
    }

    @DeleteMapping("/api/videos/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id, HttpServletRequest request) {
        media.delete(userId(request), id);
        return ResponseEntity.ok(new StatusResponse("ok"));
    }

    private static String userId(HttpServletRequest request) {
        return RequestAuth.require(request).user().id();
    }

    private record StatusResponse(String status) {
    }
}
