package com.jmr.jdms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the document API as a given user. */
public final class DocumentsApi {

    private final MockMvc mvc;

    public DocumentsApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions upload(TestJwts.Builder user, String metadataJson, byte[] content, String fileName,
            String idempotencyKey) throws Exception {
        MultipartBody body = MultipartBody.create().json("metadata", metadataJson)
                .file("file", fileName, "application/pdf", content);
        MockHttpServletRequestBuilder request = post("/api/v1/documents").contentType(body.contentType())
                .content(body.bytes());
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return as(user, request);
    }

    /** Uploads a RECEIPT for CIF {@code cif} in branch 001 and returns the response body. */
    public JsonNode uploadReceipt(TestJwts.Builder user, byte[] content, String cif) throws Exception {
        return json(upload(user, receipt(cif, "001"), content, "receipt.pdf", key()).andReturn()
                .getResponse().getContentAsString());
    }

    public ResultActions download(TestJwts.Builder user, String documentId, String versionId) throws Exception {
        return as(user, get("/api/v1/documents/" + documentId + "/versions/" + versionId + "/content"));
    }

    public ResultActions as(TestJwts.Builder user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", user.bearer()));
    }

    public static String receipt(String cif, String branchCode) {
        return "{\"documentTypeCode\":\"RECEIPT\",\"title\":\"Receipt\",\"branchCode\":\"" + branchCode
                + "\",\"cif\":\"" + cif + "\",\"customFields\":{\"amount\":\"125.500\",\"note\":\"ok\","
                + "\"secret_note\":\"hush\"}}";
    }

    public static String key() {
        return "test-" + UUID.randomUUID();
    }

    public static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static JsonNode json(String body) {
        return JsonMapper.shared().readTree(body);
    }
}
