package com.jmr.jdms.worker.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Files for the pipeline tests, generated at run time (no binary fixtures, no EICAR file in the repository). */
public final class TestFiles {

    private TestFiles() {
    }

    /** The EICAR antivirus test string, assembled so the source itself is not flagged. */
    public static byte[] eicar() {
        return ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] textPdf(String... lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(PDRectangle.A4);
                document.addPage(pdfPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 760);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLineAtOffset(0, -18);
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A minimal Word document (Office Open XML). */
    public static byte[] docx(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml"
                     ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Target="word/document.xml"
                     Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>
                    </Relationships>""");
            entry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body></w:document>""".formatted(text));
        }
        return bytes.toByteArray();
    }

    /**
     * A "scanned" page with an English and an Arabic line, rendered by Gotenberg's Chromium (proper Arabic shaping)
     * at a typical scan text size.
     */
    public static byte[] scannedPng(String gotenbergUrl) throws IOException, InterruptedException {
        String html = """
                <!doctype html><html><head><meta charset="utf-8"><style>body{background:#fff;color:#000;
                font-size:16px;font-family:"DejaVu Sans",sans-serif;margin:30px}</style></head><body>
                <p>Account statement SAMPLE BANK 2026</p><p dir="rtl" lang="ar">كشف حساب البنك التجريبي</p>
                </body></html>""";
        String boundary = "jdms" + UUID.randomUUID().toString().replace("-", "");
        String body = part(boundary, "files", "index.html", html) + field(boundary, "width", "600")
                + field(boundary, "height", "140") + field(boundary, "format", "png") + "--" + boundary + "--\r\n";
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(gotenbergUrl + "/forms/chromium/screenshot/html"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Screenshot failed: " + response.statusCode());
        }
        return response.body();
    }

    /**
     * A "scanned" English form (B-15): one {@code Label: value} line per entry, rendered by Gotenberg's Chromium at a
     * typical scan text size, so the text comes back only through OCR.
     */
    public static byte[] scannedForm(String gotenbergUrl, String... lines) throws IOException, InterruptedException {
        StringBuilder paragraphs = new StringBuilder();
        for (String line : lines) {
            paragraphs.append("<p>").append(line.replace("&", "&amp;").replace("<", "&lt;")).append("</p>");
        }
        String html = """
                <!doctype html><html><head><meta charset="utf-8"><style>body{background:#fff;color:#000;
                font-size:18px;font-family:"DejaVu Sans",sans-serif;margin:30px}p{margin:0 0 10px}</style></head>
                <body>""" + paragraphs + "</body></html>";
        String boundary = "jdms" + UUID.randomUUID().toString().replace("-", "");
        String body = part(boundary, "files", "index.html", html) + field(boundary, "width", "800")
                + field(boundary, "height", String.valueOf(60 + 34 * lines.length)) + field(boundary, "format", "png")
                + "--" + boundary + "--\r\n";
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(gotenbergUrl + "/forms/chromium/screenshot/html"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Screenshot failed: " + response.statusCode());
        }
        return response.body();
    }

    /** An image-only PDF of the page, scanned at 150 dpi. */
    public static byte[] scannedPdf(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            float width = image.getWidth() * 72f / 150;
            float height = image.getHeight() * 72f / 150;
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xobject, 0, 0, width, height);
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A PDF header followed by garbage. */
    public static byte[] malformedPdf() {
        byte[] garbage = random(50_000);
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, garbage, 0, header.length);
        return garbage;
    }

    /** A zip of a few hundred kilobytes that expands to {@code expandedMb} of zeros. */
    public static byte[] zipBomb(int expandedMb) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("zeros.txt"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < expandedMb; i++) {
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    public static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String part(String boundary, String name, String fileName, String content) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/html\r\n\r\n" + content + "\r\n";
    }

    private static String field(String boundary, String name, String value) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }
}
