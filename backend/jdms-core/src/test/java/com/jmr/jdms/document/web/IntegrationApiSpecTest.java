package com.jmr.jdms.document.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * B-18: the published OpenAPI description of the integration API ({@code docs/api/jdms-integration-v1.openapi.yaml})
 * names exactly the endpoints the controller serves and exactly the upload fields it reads, so the document handed to
 * bank system vendors cannot drift from the code.
 */
class IntegrationApiSpecTest {

    private static final Path SPEC = Path.of("../../docs/api/jdms-integration-v1.openapi.yaml");
    private static final String BASE = "/integration/v1";

    @Test
    void theSpecNamesExactlyTheServedEndpoints() throws Exception {
        assertThat(specEndpoints()).isEqualTo(servedEndpoints());
    }

    @Test
    void theSpecNamesExactlyTheUploadFields() throws Exception {
        Set<String> fields = Arrays.stream(IntegrationDocumentController.IntegrationUpload.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
        assertThat(new TreeSet<>(properties("UploadMetadata"))).isEqualTo(fields);
    }

    private static Set<String> servedEndpoints() {
        String base = IntegrationDocumentController.class.getAnnotation(RequestMapping.class).value()[0];
        Set<String> endpoints = new TreeSet<>();
        for (Method method : IntegrationDocumentController.class.getDeclaredMethods()) {
            mapping(method).ifPresent(mapping -> endpoints.add(mapping.getKey() + " " + base + mapping.getValue()));
        }
        return endpoints;
    }

    private static java.util.Optional<Map.Entry<String, String>> mapping(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return java.util.Optional.of(Map.entry("GET", path(method.getAnnotation(GetMapping.class).value(),
                    method.getAnnotation(GetMapping.class).path())));
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return java.util.Optional.of(Map.entry("POST", path(method.getAnnotation(PostMapping.class).value(),
                    method.getAnnotation(PostMapping.class).path())));
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return java.util.Optional.of(Map.entry("PUT", path(method.getAnnotation(PutMapping.class).value(),
                    method.getAnnotation(PutMapping.class).path())));
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return java.util.Optional.of(Map.entry("PATCH", path(method.getAnnotation(PatchMapping.class).value(),
                    method.getAnnotation(PatchMapping.class).path())));
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return java.util.Optional.of(Map.entry("DELETE", path(method.getAnnotation(DeleteMapping.class).value(),
                    method.getAnnotation(DeleteMapping.class).path())));
        }
        return java.util.Optional.empty();
    }

    private static String path(String[] value, String[] path) {
        String[] chosen = value.length > 0 ? value : path;
        return chosen.length > 0 ? chosen[0] : "";
    }

    @SuppressWarnings("unchecked")
    private static Set<String> specEndpoints() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) spec().get("paths");
        Set<String> endpoints = new TreeSet<>();
        paths.forEach((path, item) -> ((Map<String, Object>) item).keySet().stream()
                .filter(key -> Set.of("get", "post", "put", "patch", "delete").contains(key))
                .forEach(method -> endpoints.add(method.toUpperCase() + " " + BASE + path)));
        return endpoints;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> properties(String schema) throws Exception {
        Map<String, Object> components = (Map<String, Object>) spec().get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        return ((Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties")).keySet();
    }

    private static Map<String, Object> spec() throws Exception {
        assertThat(SPEC).as("OpenAPI file next to the docs").exists();
        try (InputStream in = Files.newInputStream(SPEC)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
    }
}
