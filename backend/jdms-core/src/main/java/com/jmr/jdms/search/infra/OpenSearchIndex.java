package com.jmr.jdms.search.infra;

import com.jmr.jdms.search.domain.SearchIndex;
import com.jmr.jdms.search.domain.SearchUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SearchIndex} over OpenSearch's REST API (JSON over HTTP; no client library). Each tenant has an alias
 * {@code <prefix>-<tenant>-documents} in front of a concrete index, so a rebuild can switch atomically. Writes use
 * external versions ({@code external_gte}): a stale write is refused, a repeated one is harmless.
 */
@Component
class OpenSearchIndex implements SearchIndex {

    /** Bulk requests are split at about this size (characters of NDJSON). */
    private static final int MAX_BULK_CHARS = 8 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper jsonMapper;
    private final String url;
    private final String prefix;
    private final Duration timeout;
    private final String indexDefinition;
    private final Set<String> knownAliases = ConcurrentHashMap.newKeySet();

    OpenSearchIndex(JsonMapper jsonMapper, @Value("${jdms.search.url}") String url,
            @Value("${jdms.search.index-prefix:jdms}") String prefix,
            @Value("${jdms.search.timeout:PT30S}") Duration timeout) {
        this.jsonMapper = jsonMapper;
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.prefix = prefix;
        this.timeout = timeout;
        try (InputStream in = getClass().getResourceAsStream("/search/document-index.json")) {
            this.indexDefinition = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The first index has a fixed name ({@code <alias>-v1}), so concurrent first writes, on this node or another,
     * cannot create two indices behind one alias: the second create fails and the alias is there.
     */
    @Override
    public synchronized void ensureIndex(String tenantCode) {
        String alias = alias(tenantCode);
        if (knownAliases.contains(alias)) {
            return;
        }
        if (send("HEAD", "/_alias/" + alias, null).statusCode() != 200) {
            ObjectNode definition = (ObjectNode) jsonMapper.readTree(indexDefinition);
            definition.putObject("aliases").putObject(alias).put("is_write_index", true);
            HttpResponse<String> created = send("PUT", "/" + alias + "-v1", definition.toString());
            if (created.statusCode() >= 300 && send("HEAD", "/_alias/" + alias, null).statusCode() != 200) {
                throw failure("create index", created);
            }
        }
        awaitReady(alias);
        knownAliases.add(alias);
    }

    /** {@code require_alias}: never auto-create an unmapped index; if the alias was removed, recreate it once. */
    @Override
    public void put(String tenantCode, Entry entry) {
        HttpResponse<String> response = putDocument(tenantCode, entry);
        if (response.statusCode() == 404) {
            knownAliases.remove(alias(tenantCode));
            ensureIndex(tenantCode);
            response = putDocument(tenantCode, entry);
        }
        if (response.statusCode() >= 300 && response.statusCode() != 409) { // 409: a newer version is indexed
            throw failure("index document", response);
        }
    }

    private HttpResponse<String> putDocument(String tenantCode, Entry entry) {
        return send("PUT", "/" + alias(tenantCode) + "/_doc/" + entry.documentId() + "?version=" + entry.version()
                + "&version_type=external_gte&refresh=wait_for&require_alias=true", entry.source().toString());
    }

    @Override
    public void delete(String tenantCode, String documentId) {
        HttpResponse<String> response = send("DELETE", "/" + alias(tenantCode) + "/_doc/" + documentId
                + "?refresh=wait_for", null);
        if (response.statusCode() >= 300 && response.statusCode() != 404) {
            throw failure("delete document", response);
        }
    }

    @Override
    public JsonNode search(String tenantCode, ObjectNode body) {
        ensureIndex(tenantCode); // waits until a new index can answer
        HttpResponse<String> response = send("POST", "/" + alias(tenantCode) + "/_search", body.toString());
        if (response.statusCode() == 404) {
            return jsonMapper.readTree("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}"); // nothing indexed yet
        }
        if (response.statusCode() >= 300) {
            throw failure("search", response);
        }
        return jsonMapper.readTree(response.body());
    }

    @Override
    public String createIndex(String tenantCode) {
        String index = newIndexName(tenantCode);
        // no refreshes while the rebuild loads it (completeBulkLoad turns them back on)
        ObjectNode definition = (ObjectNode) jsonMapper.readTree(indexDefinition);
        definition.withObject("settings").put("refresh_interval", "-1");
        HttpResponse<String> response = send("PUT", "/" + index, definition.toString());
        if (response.statusCode() >= 300) {
            throw failure("create index", response);
        }
        awaitReady(index);
        return index;
    }

    /** A new index answers searches only once its primary shard is started. */
    private void awaitReady(String index) {
        HttpResponse<String> health = send("GET", "/_cluster/health/" + index
                + "?wait_for_status=yellow&timeout=30s", null);
        if (health.statusCode() >= 300) {
            throw failure("wait for index", health);
        }
    }

    @Override
    public void bulkPut(String target, List<Entry> entries) {
        StringBuilder body = new StringBuilder();
        for (Entry entry : entries) {
            ObjectNode action = jsonMapper.createObjectNode();
            action.putObject("index").put("_index", target).put("_id", entry.documentId())
                    .put("version", entry.version()).put("version_type", "external_gte");
            body.append(action).append('\n').append(entry.source()).append('\n');
            if (body.length() >= MAX_BULK_CHARS) {
                sendBulk(body.toString());
                body.setLength(0);
            }
        }
        if (!body.isEmpty()) {
            sendBulk(body.toString());
        }
    }

    private void sendBulk(String body) {
        HttpResponse<String> response = send("POST", "/_bulk", body, "application/x-ndjson");
        if (response.statusCode() >= 300) {
            throw failure("bulk index", response);
        }
        JsonNode result = jsonMapper.readTree(response.body());
        if (result.path("errors").asBoolean()) {
            for (JsonNode item : result.path("items")) {
                int status = item.path("index").path("status").asInt();
                if (status >= 300 && status != 409) {
                    throw new SearchUnavailableException("Bulk indexing failed: " + item.path("index").path("error")
                            .path("type").asString(), null);
                }
            }
        }
    }

    @Override
    public void dropIndex(String index) {
        HttpResponse<String> response = send("DELETE", "/" + index, null);
        if (response.statusCode() >= 300 && response.statusCode() != 404) {
            throw failure("drop index", response);
        }
    }

    @Override
    public void completeBulkLoad(String index) {
        HttpResponse<String> settings = send("PUT", "/" + index + "/_settings",
                "{\"index\":{\"refresh_interval\":null}}");
        if (settings.statusCode() >= 300) {
            throw failure("restore refresh interval", settings);
        }
        refresh(index);
    }

    @Override
    public void refresh(String target) {
        HttpResponse<String> response = send("POST", "/" + target + "/_refresh", null);
        if (response.statusCode() >= 300) {
            throw failure("refresh", response);
        }
    }

    @Override
    public Map<String, Long> versions(String target, Collection<String> documentIds) {
        Map<String, Long> versions = new HashMap<>();
        if (documentIds.isEmpty()) {
            return versions;
        }
        ObjectNode request = jsonMapper.createObjectNode();
        documentIds.forEach(request.putArray("ids")::add);
        HttpResponse<String> response = send("POST", "/" + target + "/_mget?_source=false", request.toString());
        if (response.statusCode() >= 300) {
            throw failure("read versions", response);
        }
        for (JsonNode doc : jsonMapper.readTree(response.body()).path("docs")) {
            if (doc.path("found").asBoolean()) {
                versions.put(doc.path("_id").asString(), doc.path("_version").asLong());
            }
        }
        return versions;
    }

    @Override
    public void switchAlias(String tenantCode, String index) {
        String alias = alias(tenantCode);
        HttpResponse<String> current = send("GET", "/_alias/" + alias, null);
        List<String> previous = new ArrayList<>();
        if (current.statusCode() == 200) {
            jsonMapper.readTree(current.body()).propertyNames().forEach(previous::add);
        }
        ObjectNode actions = jsonMapper.createObjectNode();
        var list = actions.putArray("actions");
        previous.forEach(old -> list.addObject().putObject("remove").put("index", old).put("alias", alias));
        list.addObject().putObject("add").put("index", index).put("alias", alias).put("is_write_index", true);
        HttpResponse<String> switched = send("POST", "/_aliases", actions.toString());
        if (switched.statusCode() >= 300) {
            throw failure("switch alias", switched);
        }
        knownAliases.add(alias);
        previous.stream().filter(old -> !old.equals(index)).forEach(old -> send("DELETE", "/" + old, null));
    }

    @Override
    public String aliasOf(String tenantCode) {
        return alias(tenantCode);
    }

    String alias(String tenantCode) {
        return prefix + "-" + tenantCode.toLowerCase() + "-documents";
    }

    private String newIndexName(String tenantCode) {
        return alias(tenantCode) + "-" + Instant.now().toEpochMilli();
    }

    private HttpResponse<String> send(String method, String path, String body) {
        return send(method, path, body, "application/json");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(timeout)
                .header("Content-Type", contentType)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SearchUnavailableException("Search engine unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchUnavailableException("Interrupted", e);
        }
    }

    /** Status and error type only: responses can echo indexed text. */
    private SearchUnavailableException failure(String what, HttpResponse<String> response) {
        String type = "";
        try {
            type = jsonMapper.readTree(response.body()).path("error").path("type").asString();
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return new SearchUnavailableException("OpenSearch " + what + " failed: HTTP " + response.statusCode()
                + (type.isEmpty() ? "" : " " + type), null);
    }
}
