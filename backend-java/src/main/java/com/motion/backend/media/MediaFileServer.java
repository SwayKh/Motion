package com.motion.backend.media;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Range-capable media streaming that mirrors the {@code http.ServeContent} for stored files.
 *
 * <p>The byte source is seekable and may decrypt {@code MOTIONE1} chunks on demand. Bytes are never
 * collected into a single in-memory buffer and encrypted media is never written back to disk as
 * plaintext.</p>
 */
@Component
public class MediaFileServer {

    private record ByteRange(long start, long end) {
        long length() {
            return end - start + 1;
        }
    }

    public record ServeOptions(String contentType, String cacheControl, boolean includeDisposition) {
        public static ServeOptions media(boolean attachment) {
            return new ServeOptions(null, null, true);
        }

        public static ServeOptions sprite() {
            return new ServeOptions("image/jpeg", "private, max-age=3600", false);
        }
    }

    private static final class RangeParseException extends RuntimeException {
        private final boolean noOverlap;

        private RangeParseException(String message, boolean noOverlap) {
            super(message);
            this.noOverlap = noOverlap;
        }
    }

    private enum CondResult { NONE, TRUE, FALSE }

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".m4v", "video/x-m4v"),
            Map.entry(".mov", "video/quicktime"),
            Map.entry(".mkv", "video/x-matroska"),
            Map.entry(".webm", "video/webm"),
            Map.entry(".avi", "video/x-msvideo"),
            Map.entry(".mpeg", "video/mpeg"),
            Map.entry(".mpg", "video/mpeg"),
            Map.entry(".3gp", "video/3gpp"),
            Map.entry(".mp3", "audio/mpeg"),
            Map.entry(".m4a", "audio/mp4"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"));

    public ResponseEntity<StreamingResponseBody> serve(HttpServletRequest request,
                                                        MediaService.ServableFile file,
                                                        boolean attachment) {
        return serve(request, file, attachment, ServeOptions.media(attachment));
    }

    public ResponseEntity<StreamingResponseBody> serve(HttpServletRequest request,
                                                        MediaService.ServableFile file,
                                                        boolean attachment,
                                                        ServeOptions options) {
        MediaSource source = file.source();
        boolean head = isHead(request);
        boolean bodyOwnsSource = false;
        try {
            long size = file.size();
            String contentType = options.contentType() != null
                    ? options.contentType() : contentType(file.name());

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            if (options.includeDisposition()) {
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition(attachment ? "attachment" : "inline", file.name()));
            }
            if (options.cacheControl() != null) {
                headers.set(HttpHeaders.CACHE_CONTROL, options.cacheControl());
            }

            // Go ServeContent checkPreconditions: zero modtime, no server ETag set.
            PreconditionOutcome pre = checkPreconditions(request, headers);
            if (pre.done) {
                return ResponseEntity.status(pre.status).headers(headers).build();
            }

            String rangeHeader = pre.rangeHeader;
            List<ByteRange> ranges = null;
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                try {
                    ranges = parseRanges(rangeHeader, size);
                } catch (RangeParseException exception) {
                    if (exception.noOverlap && size == 0) {
                        ranges = null; // ignore range on empty file like Go
                    } else {
                        if (exception.noOverlap) {
                            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                        }
                        return rangeError(headers, exception.getMessage());
                    }
                }
            }

            if (ranges != null && !ranges.isEmpty()) {
                long total = 0;
                for (ByteRange range : ranges) {
                    total += range.length();
                }
                if (total > size) {
                    ranges = null; // dumb/attack range set: ignore
                }
            }

            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            if (ranges == null || ranges.isEmpty()) {
                headers.setContentLength(size);
                bodyOwnsSource = !head;
                return ResponseEntity.ok().headers(headers)
                        .body(stream(source, 0, size, head));
            }
            if (ranges.size() == 1) {
                ByteRange range = ranges.get(0);
                headers.set(HttpHeaders.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + range.end() + "/" + size);
                headers.setContentLength(range.length());
                bodyOwnsSource = !head;
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                        .body(stream(source, range.start(), range.length(), head));
            }

            String boundary = multipartBoundary();
            byte[][] preamble = multipartPreamble(ranges, contentType, size, boundary);
            byte[] closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
            long total = closing.length;
            for (byte[] chunk : preamble) {
                total += chunk.length;
            }
            for (ByteRange range : ranges) {
                total += range.length() + 2; // data + CRLF
            }

            headers.set(HttpHeaders.CONTENT_TYPE, "multipart/byteranges; boundary=" + boundary);
            headers.setContentLength(total);
            bodyOwnsSource = !head;
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                    .body(multipartStream(source, ranges, preamble, closing, head));
        } finally {
            if (!bodyOwnsSource) {
                closeQuietly(source);
            }
        }
    }

    private static ResponseEntity<StreamingResponseBody> rangeError(HttpHeaders headers, String message) {
        headers.remove(HttpHeaders.CACHE_CONTROL);
        headers.remove(HttpHeaders.CONTENT_ENCODING);
        headers.remove(HttpHeaders.ETAG);
        headers.remove(HttpHeaders.LAST_MODIFIED);
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8");
        headers.set("X-Content-Type-Options", "nosniff");
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        headers.setContentLength(body.length);
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .headers(headers)
                .body(outputStream -> outputStream.write(body));
    }

    private record PreconditionOutcome(boolean done, HttpStatus status, String rangeHeader) {
        static PreconditionOutcome done(HttpStatus status) {
            return new PreconditionOutcome(true, status, "");
        }

        static PreconditionOutcome proceed(String rangeHeader) {
            return new PreconditionOutcome(false, HttpStatus.OK, rangeHeader);
        }
    }

    private static PreconditionOutcome checkPreconditions(HttpServletRequest request, HttpHeaders responseHeaders) {
        CondResult ch = checkIfMatch(request, responseHeaders);
        if (ch == CondResult.NONE) {
            ch = checkIfUnmodifiedSince(request); // modtime is always zero in our usage
        }
        if (ch == CondResult.FALSE) {
            return PreconditionOutcome.done(HttpStatus.PRECONDITION_FAILED);
        }

        switch (checkIfNoneMatch(request, responseHeaders)) {
            case FALSE -> {
                String method = request.getMethod();
                if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    writeNotModified(responseHeaders);
                    return PreconditionOutcome.done(HttpStatus.NOT_MODIFIED);
                } else {
                    return PreconditionOutcome.done(HttpStatus.PRECONDITION_FAILED);
                }
            }
            case NONE -> {
                if (checkIfModifiedSince(request) == CondResult.FALSE) {
                    writeNotModified(responseHeaders);
                    return PreconditionOutcome.done(HttpStatus.NOT_MODIFIED);
                }
            }
            case TRUE -> {
            }
        }

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && !rangeHeader.isBlank()
                && checkIfRange(request, responseHeaders) == CondResult.FALSE) {
            rangeHeader = "";
        }
        return PreconditionOutcome.proceed(rangeHeader == null ? "" : rangeHeader);
    }

    private static void writeNotModified(HttpHeaders headers) {
        headers.remove(HttpHeaders.CONTENT_TYPE);
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.CONTENT_ENCODING);
        if (headers.getFirst(HttpHeaders.ETAG) != null) {
            headers.remove(HttpHeaders.LAST_MODIFIED);
        }
    }

    private static CondResult checkIfMatch(HttpServletRequest request, HttpHeaders responseHeaders) {
        String im = request.getHeader("If-Match");
        if (im == null || im.isBlank()) {
            return CondResult.NONE;
        }
        String value = im.trim();
        while (!value.isEmpty()) {
            value = trimLeft(value);
            if (value.isEmpty()) {
                break;
            }
            if (value.charAt(0) == ',') {
                value = value.substring(1);
                continue;
            }
            if (value.charAt(0) == '*') {
                return CondResult.TRUE;
            }
            ETagScan scan = scanETag(value);
            if (scan == null) {
                break;
            }
            if (etagStrongMatch(scan.etag, responseHeaders.getFirst(HttpHeaders.ETAG))) {
                return CondResult.TRUE;
            }
            value = scan.remain;
        }
        return CondResult.FALSE;
    }

    private static CondResult checkIfUnmodifiedSince(HttpServletRequest request) {
        // modtime is always zero in our ServeContent usage, so this condition never applies.
        return CondResult.NONE;
    }

    private static CondResult checkIfNoneMatch(HttpServletRequest request, HttpHeaders responseHeaders) {
        String inm = request.getHeader("If-None-Match");
        if (inm == null || inm.isBlank()) {
            return CondResult.NONE;
        }
        String value = inm.trim();
        while (!value.isEmpty()) {
            value = trimLeft(value);
            if (value.isEmpty()) {
                break;
            }
            if (value.charAt(0) == ',') {
                value = value.substring(1);
                continue;
            }
            if (value.charAt(0) == '*') {
                return CondResult.FALSE;
            }
            ETagScan scan = scanETag(value);
            if (scan == null) {
                break;
            }
            if (etagWeakMatch(scan.etag, responseHeaders.getFirst(HttpHeaders.ETAG))) {
                return CondResult.FALSE;
            }
            value = scan.remain;
        }
        return CondResult.TRUE;
    }

    private static CondResult checkIfModifiedSince(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return CondResult.NONE;
        }
        // modtime is always zero in our ServeContent usage, so this condition never applies.
        return CondResult.NONE;
    }

    private static CondResult checkIfRange(HttpServletRequest request, HttpHeaders responseHeaders) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return CondResult.NONE;
        }
        String ir = request.getHeader("If-Range");
        if (ir == null || ir.isBlank()) {
            return CondResult.NONE;
        }
        ETagScan scan = scanETag(ir.trim());
        if (scan != null) {
            return etagStrongMatch(scan.etag, responseHeaders.getFirst(HttpHeaders.ETAG))
                    ? CondResult.TRUE : CondResult.FALSE;
        }
        // Date-based If-Range cannot match because modtime is zero.
        return CondResult.FALSE;
    }

    private record ETagScan(String etag, String remain) {
    }

    private static ETagScan scanETag(String value) {
        String s = value.trim();
        int start = 0;
        if (s.startsWith("W/")) {
            start = 2;
        }
        if (s.length() < start + 2 || s.charAt(start) != '"') {
            return null;
        }
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                return new ETagScan(s.substring(0, i + 1), s.substring(i + 1));
            }
            if (c == 0x21 || (c >= 0x23 && c <= 0x7E) || c >= 0x80) {
                continue;
            }
            return null;
        }
        return null;
    }

    private static boolean etagStrongMatch(String a, String b) {
        return a != null && !a.isEmpty() && a.equals(b) && a.charAt(0) == '"';
    }

    private static boolean etagWeakMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return stripWeak(a).equals(stripWeak(b));
    }

    private static String stripWeak(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }

    private static String trimLeft(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index == 0 ? value : value.substring(index);
    }

    private static StreamingResponseBody stream(MediaSource source, long start, long length, boolean head) {
        return outputStream -> {
            if (head) {
                return;
            }
            try (source) {
                source.seek(start);
                MediaCrypto.copy(source, outputStream, length);
            }
        };
    }

    private static StreamingResponseBody multipartStream(MediaSource source, List<ByteRange> ranges,
                                                         byte[][] preamble, byte[] closing, boolean head) {
        return outputStream -> {
            if (head) {
                return;
            }
            try (source) {
                for (int index = 0; index < ranges.size(); index++) {
                    outputStream.write(preamble[index]);
                    source.seek(ranges.get(index).start());
                    MediaCrypto.copy(source, outputStream, ranges.get(index).length());
                    outputStream.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                outputStream.write(closing);
            }
        };
    }

    private static byte[][] multipartPreamble(List<ByteRange> ranges, String contentType,
                                               long size, String boundary) {
        byte[][] parts = new byte[ranges.size()][];
        for (int index = 0; index < ranges.size(); index++) {
            ByteRange range = ranges.get(index);
            // the multipart.Writer writes part headers in sorted key order:
            // Content-Range, then Content-Type.
            String part = "--" + boundary + "\r\n"
                    + "Content-Range: bytes " + range.start() + "-" + range.end() + "/" + size + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "\r\n";
            parts[index] = part.getBytes(StandardCharsets.US_ASCII);
        }
        return parts;
    }

    private static String multipartBoundary() {
        // the mime/multipart writer uses a long random hex boundary. Format doesn't need to match
        // exactly for parity, but must be a valid boundary token.
        byte[] random = new byte[30];
        new java.security.SecureRandom().nextBytes(random);
        StringBuilder boundary = new StringBuilder(random.length * 2);
        for (byte value : random) {
            boundary.append(Character.forDigit((value >> 4) & 0xF, 16));
            boundary.append(Character.forDigit(value & 0xF, 16));
        }
        return boundary.toString();
    }

    private static List<ByteRange> parseRanges(String header, long size) {
        String value = header == null ? "" : header.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("bytes=")) {
            throw new RangeParseException("invalid range", false);
        }
        List<ByteRange> ranges = new ArrayList<>();
        boolean noOverlap = false;
        String[] tokens = value.substring(6).split(",", -1);
        // Go ignores headers with too many ranges (default limit 200).
        if (tokens.length > 200) {
            return null;
        }
        for (String token : tokens) {
            String spec = token.trim();
            if (spec.isEmpty()) {
                continue;
            }
            int dash = spec.indexOf('-');
            if (dash < 0) {
                throw new RangeParseException("invalid range", false);
            }
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            if (left.isEmpty()) {
                if (right.isEmpty() || right.charAt(0) == '-') {
                    throw new RangeParseException("invalid range", false);
                }
                long suffix;
                try {
                    suffix = Long.parseLong(right);
                } catch (NumberFormatException exception) {
                    throw new RangeParseException("invalid range", false);
                }
                if (suffix < 0) {
                    throw new RangeParseException("invalid range", false);
                }
                if (suffix > size) {
                    suffix = size;
                }
                ranges.add(new ByteRange(size - suffix, size - 1));
                continue;
            }

            long start;
            try {
                start = Long.parseLong(left);
            } catch (NumberFormatException exception) {
                throw new RangeParseException("invalid range", false);
            }
            if (start < 0) {
                throw new RangeParseException("invalid range", false);
            }
            if (start >= size) {
                noOverlap = true;
                continue;
            }
            if (right.isEmpty()) {
                ranges.add(new ByteRange(start, size - 1));
                continue;
            }
            long end;
            try {
                end = Long.parseLong(right);
            } catch (NumberFormatException exception) {
                throw new RangeParseException("invalid range", false);
            }
            if (start > end) {
                throw new RangeParseException("invalid range", false);
            }
            if (end >= size) {
                end = size - 1;
            }
            ranges.add(new ByteRange(start, end));
        }
        if (noOverlap && ranges.isEmpty()) {
            throw new RangeParseException("invalid range: failed to overlap", true);
        }
        return ranges.isEmpty() ? null : ranges;
    }

    private static void closeQuietly(MediaSource source) {
        try {
            source.close();
        } catch (IOException ignored) {
            // Best-effort cleanup on an error response.
        }
    }

    private static String contentType(String name) {
        String extension = name.contains(".")
                ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static String contentDisposition(String disposition, String name) {
        String filename = name == null ? "" : name;
        if (isAscii(filename) && isTokenValue(filename)) {
            return disposition + "; filename=" + filename;
        }
        if (isAscii(filename)) {
            String escaped = filename.replace("\\", "\\\\").replace("\"", "\\\"");
            return disposition + "; filename=\"" + escaped + "\"";
        }
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        // Match the mime.FormatMediaType: for non-ASCII filenames it emits filename* only.
        return disposition + "; filename*=utf-8''" + encoded;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenValue(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isTokenChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenChar(char c) {
        if (c <= 0x20 || c >= 0x7F) {
            return false;
        }
        return switch (c) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}', ' ', '\t' -> false;
            default -> true;
        };
    }

    private static boolean isHead(HttpServletRequest request) {
        return "HEAD".equalsIgnoreCase(request.getMethod());
    }
}
