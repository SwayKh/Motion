package com.motion.backend.web.api;

import com.motion.backend.auth.ApiException;
import com.motion.backend.processing.JobConflictException;
import com.motion.backend.processing.JobQuotaException;
import com.motion.backend.timeline.TimelineValidationException;
import com.motion.backend.timeline.TimelineValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiError(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(JobConflictException.class)
    public ResponseEntity<ApiError> jobConflict(JobConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(JobQuotaException.class)
    public ResponseEntity<ApiError> jobQuota(JobQuotaException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(TimelineValidationException.class)
    public ResponseEntity<TimelineValidationResponse> timelineValidation(TimelineValidationException exception) {
        return ResponseEntity.status(exception.status())
                .body(new TimelineValidationResponse(exception.getMessage(), exception.errors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadableBody(HttpMessageNotReadableException exception) {
        return new ApiError("invalid request body");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError missingParameter(MissingServletRequestParameterException exception) {
        return new ApiError("missing required parameter");
    }

    /** Go wraps the whole multipart body at 150 MiB and reports a fixed client message. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                .body(new ApiError("file exceeds the 150 MB limit"));
    }

    /** Any other multipart parse failure maps to the generic invalid-upload error. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> invalidMultipart(MultipartException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("invalid multipart upload"));
    }

    /**
     * the ServeMux emits plain text for unmatched routes; JSON error envelopes only come from
     * application handlers via {@code writeError}.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> notFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("404 page not found\n");
    }

    /** Mirrors the 405 body and Allow header, including the implicit HEAD for GET routes. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> methodNotSupported(HttpRequestMethodNotSupportedException exception) {
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            List<String> methods = new ArrayList<>();
            for (HttpMethod method : supported) {
                methods.add(method.name());
            }
            if (methods.contains("GET") && !methods.contains("HEAD")) {
                methods.add("HEAD");
            }
            headers.set(HttpHeaders.ALLOW, String.join(", ", methods));
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(headers)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Method Not Allowed\n");
    }

    /**
     * Application errors that are not one of the explicit {@code writeError} cases still produce
     * the Go-shaped JSON envelope instead of a Spring Boot error page.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError unexpectedFailure(Exception exception) {
        log.error("unhandled request failure", exception);
        return new ApiError("internal server error");
    }
}
