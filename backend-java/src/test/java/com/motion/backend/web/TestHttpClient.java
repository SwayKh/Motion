package com.motion.backend.web;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tiny stateful HTTP client so tests can assert exact cookies and JSON wire responses. */
final class TestHttpClient {

    private final TestRestTemplate restTemplate;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private List<String> lastSetCookies = List.of();
    private String forwardedFor;

    TestHttpClient(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    void clearCookies() {
        cookies.clear();
    }

    void copyCookiesFrom(TestHttpClient source) {
        cookies.putAll(source.cookies);
    }

    void useForwardedFor(String address) {
        this.forwardedFor = address;
    }

    String cookie(String name) {
        return cookies.get(name);
    }

    List<String> lastSetCookies() {
        return lastSetCookies;
    }

    ResponseEntity<String> get(String path) {
        return get(path, null);
    }

    ResponseEntity<String> get(String path, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.GET, null, String.class, false, null, additionalHeaders);
    }

    ResponseEntity<byte[]> getBytes(String path, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.GET, null, byte[].class, false, null, additionalHeaders);
    }

    ResponseEntity<String> head(String path, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.HEAD, null, String.class, false, null, additionalHeaders);
    }

    ResponseEntity<String> options(String path, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.OPTIONS, null, String.class, false, null, additionalHeaders);
    }

    ResponseEntity<String> post(String path, String json) {
        return post(path, json, false, null);
    }

    ResponseEntity<String> postWithCsrf(String path, String json) {
        return post(path, json, true, null);
    }

    ResponseEntity<String> post(String path, String json, boolean useCsrf, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.POST, json, String.class, useCsrf,
                MediaType.APPLICATION_JSON_VALUE, additionalHeaders);
    }

    ResponseEntity<String> put(String path, String json) {
        return put(path, json, false, null);
    }

    ResponseEntity<String> putWithCsrf(String path, String json) {
        return put(path, json, true, null);
    }

    ResponseEntity<String> put(String path, String json, boolean useCsrf, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.PUT, json, String.class, useCsrf,
                MediaType.APPLICATION_JSON_VALUE, additionalHeaders);
    }

    ResponseEntity<String> delete(String path) {
        return delete(path, false, null);
    }

    ResponseEntity<String> deleteWithCsrf(String path) {
        return delete(path, true, null);
    }

    ResponseEntity<String> delete(String path, boolean useCsrf, HttpHeaders additionalHeaders) {
        return exchange(path, HttpMethod.DELETE, null, String.class, useCsrf, null, additionalHeaders);
    }

    /**
     * Builds a real multipart request without relying on a client-side multipart converter, so
     * tests control the exact field name, filename, bytes, and boundary.
     */
    ResponseEntity<String> postMultipart(String path, String fieldName, String filename, byte[] content,
                                         boolean useCsrf, HttpHeaders additionalHeaders) {
        String boundary = "----motion-test-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, fieldName, filename, content);
        return exchange(path, HttpMethod.POST, body, String.class, useCsrf,
                "multipart/form-data; boundary=" + boundary, additionalHeaders);
    }

    private static byte[] multipartBody(String boundary, String fieldName, String filename, byte[] content) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + fieldName + "\""
                    + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(content);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body, Class<T> responseType,
                                           boolean useCsrf, String contentType, HttpHeaders additionalHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(MediaType.parseMediaType(contentType));
        }
        if (!cookies.isEmpty()) {
            headers.set(HttpHeaders.COOKIE, cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse(""));
        }
        if (useCsrf && cookies.containsKey("motion_csrf")) {
            headers.set("X-CSRF-Token", cookies.get("motion_csrf"));
        }
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        if (additionalHeaders != null) {
            additionalHeaders.forEach((key, values) -> headers.put(key, new ArrayList<>(values)));
        }

        ResponseEntity<T> response = restTemplate.exchange(
                path, method, new HttpEntity<>(body, headers), responseType);
        captureCookies(response.getHeaders());
        return response;
    }

    private void captureCookies(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        lastSetCookies = setCookies == null ? List.of() : List.copyOf(setCookies);
        for (String value : lastSetCookies) {
            String pair = value.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals).trim();
            String cookieValue = pair.substring(equals + 1).trim();
            boolean deleted = value.toLowerCase().contains("max-age=0");
            if (deleted) {
                cookies.remove(name);
            } else {
                cookies.put(name, cookieValue);
            }
        }
    }
}
