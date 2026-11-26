package com.jmr.jdms.perf.load;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Files the ingest simulation uploads: digital PDFs (text layer) and scanned pages (PNG, OCR needed). */
final class SampleFiles {

    private static final List<String> LINES = List.of(
            "The customer confirmed the details of the account opening form in person.",
            "Signatures were compared with the specimen held on file by the branch.",
            "Know your customer checks were completed and screening returned no matches.",
            "Standing instructions for salary transfer were registered with the bank.",
            "The relationship officer verified the identity documents against the originals.");

    private SampleFiles() {
    }

    /** A digital PDF of {@code pages} pages carrying the marker word on the first page. */
    static byte[] pdf(String marker, int pages, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int page = 0; page < pages; page++) {
                PDPage pdfPage = new PDPage(PDRectangle.A4);
                document.addPage(pdfPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.newLineAtOffset(60, 780);
                    content.showText("Account opening form, reference " + marker + ", page " + (page + 1));
                    for (int line = 0; line < 40; line++) {
                        content.newLineAtOffset(0, -18);
                        content.showText(LINES.get(random.nextInt(LINES.size())));
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A scanned page at about 200 dpi: grey text on a slightly noisy background, no text layer. */
    static byte[] scan(String marker, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        BufferedImage image = new BufferedImage(1654, 2339, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (int dot = 0; dot < 20_000; dot++) {
                int grey = 200 + random.nextInt(55);
                graphics.setColor(new Color(grey, grey, grey));
                graphics.fillRect(random.nextInt(image.getWidth()), random.nextInt(image.getHeight()), 2, 2);
            }
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(30, 30, 30));
            graphics.setFont(new Font(Font.SERIF, Font.PLAIN, 34));
            graphics.drawString("SALARY CERTIFICATE " + marker, 140, 220);
            graphics.setFont(new Font(Font.SERIF, Font.PLAIN, 28));
            for (int line = 0; line < 20; line++) {
                graphics.drawString(LINES.get(random.nextInt(LINES.size())), 140, 320 + line * 52);
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
