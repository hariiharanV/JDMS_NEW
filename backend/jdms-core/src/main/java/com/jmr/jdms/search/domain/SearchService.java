package com.jmr.jdms.search.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.SearchFilter;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.document.api.IndexableDocuments;
import com.jmr.jdms.metadata.api.FieldInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Permission-trimmed full-text search (spec §4.6, §6). The caller's access filter ({@code VIEW_METADATA}) is part of
 * the query, so hits, totals and facet counts include only documents they may see; content is searched only within
 * documents they may also read ({@code VIEW_CONTENT}), so matching text never reveals a document's content to someone
 * who may only see its metadata. Every hit is re-checked against the policy and current data before it is returned,
 * and snippets are removed where the caller lacks {@code VIEW_CONTENT}. User input only ever becomes JSON values.
 */
@Service
public class SearchService {

    public static final Set<String> STANDARD_FACETS = Set.of("documentType", "branch", "status", "cabinet");
    static final int MAX_WINDOW = 10_000;
    /** Match markers in highlighter output; control characters are removed from indexed text (SearchIndexer). */
    static final String MATCH_START = "\u0001";
    static final String MATCH_END = "\u0002";
    private static final Pattern CUSTOM_KEY = Pattern.compile("[a-z][a-z0-9_]{0,49}");
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Filters on standard fields (exact), dates (inclusive ranges) and custom fields by key. */
    public record Filters(String documentType, String cabinet, String branch, String status, String classification,
            String cif, String accountNo, String contractRef, String collateralCode, String transactionRef,
            String applicationRef, String externalRef, LocalDate createdFrom, LocalDate createdTo,
            LocalDate expiryFrom, LocalDate expiryTo, Map<String, String> custom) {
    }

    /** {@code page} defaults to 0, {@code size} to 20. */
    public record Query(String query, Filters filters, List<String> facets, Integer page, Integer size) {

        public Query {
            page = page == null ? 0 : page;
            size = size == null ? 20 : size;
        }
    }

    /** A piece of a snippet; {@code match} marks the words that matched (no HTML: clients render and escape). */
    public record Segment(String text, boolean match) {
    }

    /** {@code snippets}: content fragments around the matches, only for callers with {@code VIEW_CONTENT}. */
    public record Hit(UUID id, String title, String documentTypeCode, String cabinetCode, String branchCode,
            String status, String classification, String cif, String accountNo, String createdAt,
            List<List<Segment>> snippets) {
    }

    public record Bucket(String value, long count) {
    }

    public record Result(List<Hit> items, Map<String, List<Bucket>> facets, long total, int page, int size) {
    }

    private final SearchIndex index;
    private final AccessPolicy policy;
    private final IndexableDocuments documents;
    private final MetadataCatalog catalog;
    private final AuditTrail auditTrail;
    private final JsonMapper jsonMapper;

    SearchService(SearchIndex index, AccessPolicy policy, IndexableDocuments documents, MetadataCatalog catalog,
            AuditTrail auditTrail, JsonMapper jsonMapper) {
        this.index = index;
        this.policy = policy;
        this.documents = documents;
        this.catalog = catalog;
        this.auditTrail = auditTrail;
        this.jsonMapper = jsonMapper;
    }

    public Result search(Query request) {
        Filters filters = request.filters() == null ? emptyFilters() : request.filters();
        List<String> facets = request.facets() == null ? List.of() : request.facets();
        Map<String, FieldInfo> fields = fieldsByKey();
        validate(request, filters, facets, fields);
        RequestContext caller = RequestContext.require();
        Subject subject = Subject.current();
        SearchFilter view = policy.searchFilter(subject, Action.VIEW_METADATA);
        Result result;
        if (view.matchesNothing()) {
            result = new Result(List.of(), Map.of(), 0, request.page(), request.size());
        } else {
            SearchFilter read = policy.searchFilter(subject, Action.VIEW_CONTENT);
            ObjectNode body = body(request, filters, facets, fields, view, read);
            result = result(index.search(caller.tenantCode().toLowerCase(), body), request, facets);
        }
        auditTrail.record(AuditEntry.success("SEARCH_PERFORMED", "SEARCH", "documents", Map.of(
                "hasQuery", request.query() != null && !request.query().isBlank(),
                "total", result.total(), "returned", result.items().size())));
        return result;
    }

    private ObjectNode body(Query request, Filters filters, List<String> facets, Map<String, FieldInfo> fields,
            SearchFilter view, SearchFilter read) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("from", request.page() * request.size());
        body.put("size", request.size());
        body.put("track_total_hits", true);
        body.putArray("_source").add("document_id").add("title").add("document_type_code").add("cabinet_code")
                .add("branch_code").add("status").add("classification").add("cif").add("account_no").add("created_at");

        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.add(accessFilter(view));
        userFilters(filter, filters, fields);
        if (filters.status() == null) {
            bool.putArray("must_not").addObject().putObject("terms").putArray("status").add("DELETED").add("DISPOSED");
        }
        String text = request.query() == null ? "" : request.query().strip();
        if (text.isEmpty()) {
            bool.putArray("must").addObject().putObject("match_all");
        } else {
            ObjectNode should = bool.putArray("must").addObject().putObject("bool");
            ArrayNode clauses = should.putArray("should");
            ObjectNode metadata = clauses.addObject().putObject("multi_match");
            metadata.put("query", text);
            metadata.put("type", "best_fields");
            metadata.put("lenient", true);
            metadata.putArray("fields").add("title^3").add("title.en^2").add("title.ar^2").add("customer_name")
                    .add("customer_name.ar").add("cif^4").add("account_no^4").add("contract_ref^4")
                    .add("collateral_code^4").add("transaction_ref^4").add("application_ref^4").add("external_ref^4")
                    .add("custom_text.*").add("custom_keyword.*");
            if (!read.matchesNothing()) {
                ObjectNode content = clauses.addObject().putObject("bool");
                ObjectNode match = content.putArray("must").addObject().putObject("multi_match");
                match.put("query", text);
                match.putArray("fields").add("content").add("content.en").add("content.ar");
                content.putArray("filter").add(accessFilter(read));
            }
            should.put("minimum_should_match", 1);
            ObjectNode highlight = body.putObject("highlight");
            highlight.putArray("pre_tags").add(MATCH_START);
            highlight.putArray("post_tags").add(MATCH_END);
            ObjectNode highlighted = highlight.putObject("fields");
            for (String field : List.of("content", "content.en", "content.ar")) {
                highlighted.putObject(field).put("fragment_size", 150).put("number_of_fragments", 3);
            }
        }
        body.putArray("sort").add("_score").addObject().putObject("created_at").put("order", "desc");

        if (!facets.isEmpty()) {
            ObjectNode aggs = body.putObject("aggs");
            for (String facet : facets) {
                aggs.putObject(facet).putObject("terms").put("field", facetField(facet, fields)).put("size", 50);
            }
        }
        return body;
    }

    /** The access policy's filter as an OpenSearch filter (same semantics as the database list in B-04). */
    private ObjectNode accessFilter(SearchFilter access) {
        ObjectNode root = jsonMapper.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("filter");
        ArrayNode classifications = must.addObject().putObject("terms").putArray("classification");
        access.classifications().forEach(classification -> classifications.add(classification.name()));
        ArrayNode mustNot = bool.putArray("must_not");
        access.denyScopes().forEach(deny -> mustNot.add(scope(deny)));
        ObjectNode allowed = must.addObject().putObject("bool");
        ArrayNode should = allowed.putArray("should");
        for (SearchFilter.Scope allow : access.allowScopes()) {
            ObjectNode clause = should.addObject().putObject("bool");
            ArrayNode parts = clause.putArray("filter");
            parts.add(scope(allow));
            if (!allow.allBranches()) {
                ArrayNode branches = parts.addObject().putObject("terms").putArray("branch_id");
                allow.branchIds().forEach(branch -> branches.add(branch.toString()));
            }
        }
        if (!access.grantedDocumentIds().isEmpty()) {
            ArrayNode granted = should.addObject().putObject("terms").putArray("document_id");
            access.grantedDocumentIds().forEach(id -> granted.add(id.toString()));
        }
        allowed.put("minimum_should_match", 1);
        return root;
    }

    private ObjectNode scope(SearchFilter.Scope scope) {
        ObjectNode clause = jsonMapper.createObjectNode();
        switch (scope.type()) {
            case TENANT -> clause.putObject("match_all");
            case CABINET -> clause.putObject("term").put("cabinet_id", scope.id().toString());
            case DOCUMENT_TYPE -> clause.putObject("term").put("document_type_id", scope.id().toString());
            case DOCUMENT -> clause.putObject("term").put("document_id", scope.id().toString());
            case FOLDER -> {
                // the subtree as it is now: moving folders needs no re-index
                ArrayNode folders = clause.putObject("terms").putArray("folder_id");
                catalog.folderSubtree(scope.id()).forEach(folder -> folders.add(folder.toString()));
            }
        }
        return clause;
    }

    private void userFilters(ArrayNode filter, Filters filters, Map<String, FieldInfo> fields) {
        term(filter, "document_type_code", filters.documentType());
        term(filter, "cabinet_code", filters.cabinet());
        term(filter, "branch_code", filters.branch());
        term(filter, "status", filters.status());
        term(filter, "classification", filters.classification());
        term(filter, "cif", filters.cif());
        term(filter, "account_no", filters.accountNo());
        term(filter, "contract_ref", filters.contractRef());
        term(filter, "collateral_code", filters.collateralCode());
        term(filter, "transaction_ref", filters.transactionRef());
        term(filter, "application_ref", filters.applicationRef());
        term(filter, "external_ref", filters.externalRef());
        range(filter, "created_at", filters.createdFrom(), filters.createdTo());
        range(filter, "expiry_date", filters.expiryFrom(), filters.expiryTo());
        if (filters.custom() != null) {
            filters.custom().forEach((key, value) -> term(filter, exactField(fields.get(key)), value));
        }
    }

    private static void term(ArrayNode filter, String field, String value) {
        if (value != null && !value.isBlank()) {
            filter.addObject().putObject("term").put(field, value.strip());
        }
    }

    private static void range(ArrayNode filter, String field, LocalDate from, LocalDate to) {
        if (from != null || to != null) {
            ObjectNode range = filter.addObject().putObject("range").putObject(field);
            if (from != null) {
                range.put("gte", from.toString());
            }
            if (to != null) {
                range.put("lte", to.toString());
            }
            range.put("format", "strict_date||strict_date_optional_time");
        }
    }

    private Result result(JsonNode response, Query request, List<String> facets) {
        List<JsonNode> hits = new ArrayList<>();
        response.path("hits").path("hits").forEach(hits::add);
        Set<UUID> ids = new LinkedHashSet<>();
        hits.forEach(hit -> ids.add(UUID.fromString(hit.path("_source").path("document_id").asString())));
        // defence in depth: current data and rules, not only the index
        Set<UUID> viewable = ids.isEmpty() ? Set.of() : documents.permitted(ids, Action.VIEW_METADATA);
        Set<UUID> readable = viewable.isEmpty() ? Set.of() : documents.permitted(viewable, Action.VIEW_CONTENT);
        List<Hit> items = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            UUID id = UUID.fromString(source.path("document_id").asString());
            if (!viewable.contains(id)) {
                log.warn("Search hit {} failed the policy re-check and was dropped (index or rule change in flight)",
                        id);
                continue;
            }
            List<List<Segment>> snippets = new ArrayList<>();
            if (readable.contains(id)) {
                for (String field : List.of("content", "content.en", "content.ar")) {
                    if (snippets.isEmpty()) {
                        hit.path("highlight").path(field).forEach(fragment -> snippets.add(segments(
                                fragment.asString())));
                    }
                }
            }
            items.add(new Hit(id, text(source, "title"), text(source, "document_type_code"),
                    text(source, "cabinet_code"), text(source, "branch_code"), text(source, "status"),
                    text(source, "classification"), text(source, "cif"), text(source, "account_no"),
                    text(source, "created_at"), snippets));
        }
        Map<String, List<Bucket>> buckets = new LinkedHashMap<>();
        for (String facet : facets) {
            List<Bucket> values = new ArrayList<>();
            response.path("aggregations").path(facet).path("buckets").forEach(bucket -> values.add(
                    new Bucket(bucket.path("key_as_string").isMissingNode() ? bucket.path("key").asString()
                            : bucket.path("key_as_string").asString(), bucket.path("doc_count").asLong())));
            buckets.put(facet, values);
        }
        long total = response.path("hits").path("total").path("value").asLong();
        return new Result(items, buckets, total, request.page(), request.size());
    }

    private void validate(Query request, Filters filters, List<String> facets, Map<String, FieldInfo> fields) {
        List<Violation> violations = new ArrayList<>();
        if (request.size() < 1 || request.size() > 100) {
            violations.add(new Violation("size", "SIZE_INVALID", "Must be 1 to 100."));
        }
        if (request.page() < 0 || (long) (request.page() + 1) * Math.max(1, request.size()) > MAX_WINDOW) {
            violations.add(new Violation("page", "PAGE_INVALID", "Results are available up to " + MAX_WINDOW + "."));
        }
        if (request.query() != null && request.query().length() > 500) {
            violations.add(new Violation("query", "QUERY_TOO_LONG", "At most 500 characters."));
        }
        for (String facet : facets) {
            if (!STANDARD_FACETS.contains(facet) && !isCustomFacet(facet, fields)) {
                violations.add(new Violation("facets", "FACET_UNKNOWN", "Unknown facet: " + facet));
            }
        }
        if (filters.custom() != null) {
            filters.custom().keySet().forEach(key -> {
                FieldInfo field = fields.get(key);
                if (field == null || !field.searchable()) {
                    violations.add(new Violation("filters.custom." + key, "FIELD_NOT_SEARCHABLE",
                            "Not a searchable custom field."));
                }
            });
        }
        ValidationFailedException.throwIfAny("The search request is invalid.", violations);
    }

    private static boolean isCustomFacet(String facet, Map<String, FieldInfo> fields) {
        if (!facet.startsWith("custom.")) {
            return false;
        }
        FieldInfo field = fields.get(facet.substring("custom.".length()));
        return field != null && field.facet();
    }

    private static String facetField(String facet, Map<String, FieldInfo> fields) {
        return switch (facet) {
            case "documentType" -> "document_type_code";
            case "branch" -> "branch_code";
            case "status" -> "status";
            case "cabinet" -> "cabinet_code";
            default -> exactField(fields.get(facet.substring("custom.".length())));
        };
    }

    /** The field holding a custom value for exact matches and facets. */
    private static String exactField(FieldInfo field) {
        return switch (field.dataType()) {
            case "TEXT" -> "custom_text." + field.key() + ".raw";
            case "NUMBER" -> "custom_number." + field.key();
            case "DATE" -> "custom_date." + field.key();
            case "BOOLEAN" -> "custom_bool." + field.key();
            default -> "custom_keyword." + field.key();
        };
    }

    /** Non-sensitive custom fields by key (the first definition of a key wins; keys mean the same across types). */
    private Map<String, FieldInfo> fieldsByKey() {
        Map<String, FieldInfo> byKey = new LinkedHashMap<>();
        catalog.allFields().stream().filter(field -> !field.sensitive())
                .filter(field -> CUSTOM_KEY.matcher(field.key()).matches())
                .forEach(field -> byKey.merge(field.key(), field, (first, other) -> new FieldInfo(
                        first.documentTypeId(), first.key(), first.dataType(), first.searchable() || other.searchable(),
                        first.facet() || other.facet(), false)));
        return byKey;
    }

    /**
     * Splits a highlighted fragment into plain and matching text. OpenSearch trims each fragment with
     * {@code String.trim()}, which also strips the control-character markers at its edges: a fragment that begins
     * with a match arrives without its start marker, one that ends with a match without its end marker.
     */
    static List<Segment> segments(String fragment) {
        int firstStart = fragment.indexOf(MATCH_START);
        int firstEnd = fragment.indexOf(MATCH_END);
        boolean match = firstEnd >= 0 && (firstStart < 0 || firstEnd < firstStart);
        List<Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (char c : fragment.toCharArray()) {
            if (c == MATCH_START.charAt(0) || c == MATCH_END.charAt(0)) {
                if (!text.isEmpty()) {
                    segments.add(new Segment(text.toString(), match));
                    text.setLength(0);
                }
                match = c == MATCH_START.charAt(0);
            } else {
                text.append(c);
            }
        }
        if (!text.isEmpty()) {
            segments.add(new Segment(text.toString(), match));
        }
        return segments;
    }

    private static Filters emptyFilters() {
        return new Filters(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, Map.of());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
