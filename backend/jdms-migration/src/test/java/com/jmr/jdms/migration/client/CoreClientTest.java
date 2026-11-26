package com.jmr.jdms.migration.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jmr.jdms.migration.client.CoreClient.CoreException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client on the tool's own (production) classpath: the import request is a multipart body with the metadata
 * before the files, rate limits are retried, and problems surface with their code.
 */
class CoreClientTest {

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final CoreClient client = new CoreClient(builder, URI.create("http://core.test"), () -> "token-1",
            JsonMapper.shared());

    @Test
    void importsAsMultipartWithMetadataFirstAndRetriesRateLimits() {
        server.expect(requestTo("http://core.test/integration/v1/migration/documents"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "0"));
        server.expect(requestTo("http://core.test/integration/v1/migration/documents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.ISO_8859_1);
                    assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                            .isTrue();
                    int metadata = body.indexOf("name=\"metadata\"");
                    int firstFile = body.indexOf("name=\"file\"; filename=\"v1.pdf\"");
                    int secondFile = body.indexOf("name=\"file\"; filename=\"v2.pdf\"");
                    assertThat(metadata).isNotNegative().isLessThan(firstFile);
                    assertThat(firstFile).isLessThan(secondFile);
                    assertThat(body).contains("\"legacyDocumentId\":\"L1\"").contains("%PDF-one").contains("%PDF-two");
                })
                .andRespond(withSuccess("{\"documentId\":\"0190a000-0000-7000-8000-000000000001\"}",
                        MediaType.APPLICATION_JSON));

        var result = client.importDocument(Map.of("legacyDocumentId", "L1"),
                List.of("%PDF-one".getBytes(), "%PDF-two".getBytes()), List.of("v1.pdf", "v2.pdf"));

        assertThat(result.path("documentId").asString()).endsWith("0001");
        server.verify();
    }

    @Test
    void problemsCarryTheirStableCode() {
        server.expect(requestTo("http://core.test/integration/v1/migration/documents"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"VALIDATION_FAILED\",\"detail\":\"invalid\",\"errors\":"
                                + "[{\"field\":\"customFields.amount\",\"code\":\"FIELD_REQUIRED\"}]}"));

        assertThatThrownBy(() -> client.importDocument(Map.of(), List.of(), List.of()))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> {
                    CoreException problem = (CoreException) e;
                    assertThat(problem.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(problem.status()).isEqualTo(422);
                    assertThat(problem.violations()).containsExactly("customFields.amount FIELD_REQUIRED");
                });
    }
}
