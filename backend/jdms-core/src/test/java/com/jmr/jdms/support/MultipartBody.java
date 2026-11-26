package com.jmr.jdms.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;

/**
 * Builds a raw {@code multipart/form-data} body. The application parses uploads as a stream (not through the
 * servlet multipart API), so tests send real multipart bytes rather than MockMvc's pre-parsed parts.
 */
public final class MultipartBody {

    private final String boundary = "----jdms" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    public static MultipartBody create() {
        return new MultipartBody();
    }

    public MultipartBody field(String name, String value, String contentType) {
        startPart("form-data; name=\"" + name + "\"", contentType);
        write(value);
        write("\r\n");
        return this;
    }

    public MultipartBody json(String name, String json) {
        return field(name, json, MediaType.APPLICATION_JSON_VALUE);
    }

    public MultipartBody file(String name, String fileName, String contentType, byte[] content) {
        startPart("form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"", contentType);
        body.writeBytes(content);
        write("\r\n");
        return this;
    }

    public MediaType contentType() {
        return MediaType.parseMediaType("multipart/form-data; boundary=" + boundary);
    }

    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(body.toByteArray());
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void startPart(String disposition, String contentType) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: " + disposition + "\r\n");
        if (contentType != null) {
            write("Content-Type: " + contentType + "\r\n");
        }
        write("\r\n");
    }

    private void write(String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
