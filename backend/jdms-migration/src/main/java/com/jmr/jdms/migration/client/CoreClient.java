package com.jmr.jdms.migration.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core's API as the migration client uses it (ADR-0020): the import and access-check endpoints, read-back
 * through the integration API, the metadata catalog, and administration proposals for the catalog step. Rate limits
 * ({@code 429}) and outages ({@code 503}) are retried after the server's {@code Retry-After}; other failures surface
 * as {@link CoreException} with the problem's stable code.
 */
public class CoreClient {

    private static final int ATTEMPTS = 8;

    private final RestClient http;
    private final TokenProvider tokens;
    private final JsonMapper json;

    public CoreClient(RestClient.Builder builder, URI baseUrl, TokenProvider tokens, JsonMapper json) {
        this.http = builder.baseUrl(baseUrl.toString()).build();
        this.tokens = tokens;
        this.json = json;
    }

    /** A refused call: HTTP status, the problem's {@code code}, its detail and field violations. */
    public static final class CoreException extends RuntimeException {

        private final int status;
        private final String code;
        private final List<String> violations;

        CoreException(int status, String code, String detail, List<String> violations) {
            super(code + ": " + detail, null, false, false);
            this.status = status;
            this.code = code;
            this.violations = List.copyOf(violations);
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public List<String> violations() {
            return violations;
        }
    }

    /** Imports one document: the metadata JSON, then the decrypted files, oldest version first. */
    public JsonNode importDocument(Map<String, Object> metadata, List<byte[]> files, List<String> fileNames) {
        return call(() -> {
            // parts keep their order: the core reads the metadata before the files (ADR-0011)
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            HttpHeaders metadataHeaders = new HttpHeaders();
            metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
            body.add("metadata", new HttpEntity<>(json.writeValueAsString(metadata), metadataHeaders));
            for (int i = 0; i < files.size(); i++) {
                HttpHeaders fileHeaders = new HttpHeaders();
                fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                fileHeaders.setContentDisposition(ContentDisposition.formData().name("file")
                        .filename(fileNames.get(i)).build());
                body.add("file", new HttpEntity<>(files.get(i), fileHeaders));
            }
            return http.post().uri("/integration/v1/migration/documents").headers(this::auth)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(JsonNode.class);
        });
    }

    public JsonNode accessCheck(List<String> roles, String branchCode, String clearance, List<UUID> documentIds) {
        Map<String, Object> request = Map.of("roles", roles, "allBranches", false,
                "branchCodes", branchCode == null ? List.of() : List.of(branchCode), "clearance", clearance,
                "documentIds", documentIds);
        return call(() -> http.post().uri("/integration/v1/migration/access-check").headers(this::auth)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class));
    }

    /** The document as the core stores it, through the integration API. */
    public JsonNode document(UUID id) {
        return call(() -> http.get().uri("/integration/v1/documents/{id}", id).headers(this::auth).retrieve()
                .body(JsonNode.class));
    }

    public JsonNode cabinets() {
        return call(() -> http.get().uri("/api/v1/cabinets").headers(this::auth).retrieve().body(JsonNode.class));
    }

    public JsonNode documentTypes() {
        return call(() -> http.get().uri("/api/v1/document-types").headers(this::auth).retrieve()
                .body(JsonNode.class));
    }

    public JsonNode folders(UUID cabinetId) {
        return call(() -> http.get().uri("/api/v1/cabinets/{id}/folders", cabinetId).headers(this::auth).retrieve()
                .body(JsonNode.class));
    }

    /** Proposes an administration change ({@code /api/v1/admin/...}); a second administrator must approve it. */
    public JsonNode propose(String path, Map<String, Object> body) {
        return call(() -> http.post().uri(path).headers(headers -> {
            auth(headers);
            headers.set("Idempotency-Key", "migration-" + UUID.randomUUID());
        }).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
    }

    private void auth(HttpHeaders headers) {
        headers.setBearerAuth(tokens.token());
    }

    private <T> T call(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (HttpStatusCodeException e) {
                HttpStatusCode status = e.getStatusCode();
                boolean retryable = status.value() == 429 || status.value() == 503;
                if (retryable && attempt < ATTEMPTS) {
                    sleep(retryAfter(e.getResponseHeaders(), attempt));
                    continue;
                }
                throw problem(e);
            }
        }
    }

    private CoreException problem(HttpStatusCodeException e) {
        String code = "HTTP_" + e.getStatusCode().value();
        String detail = e.getStatusText();
        List<String> violations = new ArrayList<>();
        try {
            JsonNode problem = json.readTree(e.getResponseBodyAsString());
            code = problem.path("code").asString(code);
            detail = problem.path("detail").asString(detail);
            problem.path("errors").forEach(error -> violations.add(error.path("field").asString() + " "
                    + error.path("code").asString()));
        } catch (RuntimeException ignored) {
            // not a problem document: keep the HTTP status
        }
        return new CoreException(e.getStatusCode().value(), code, detail, violations);
    }

    private static Duration retryAfter(HttpHeaders headers, int attempt) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Duration.ofSeconds(Math.min(30, Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Duration.ofMillis(Math.min(30_000, 250L << attempt));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
