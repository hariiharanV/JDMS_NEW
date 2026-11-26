package com.jmr.jdms.worker.pipeline;

import com.jmr.jdms.worker.config.WorkerProperties;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * First-page thumbnails (PNG, longest side {@code size} pixels) and page counts. Best effort: anything unusual —
 * encrypted or broken PDFs, absurd page or image sizes, rendering past the timeout — yields no thumbnail rather than
 * a failure. Images are decoded subsampled, so memory follows the thumbnail size, not the image size.
 */
@Component
class Thumbnailer {

    /** {@code png} is empty when no thumbnail could be made. */
    record Result(Optional<byte[]> png, Integer pageCount) {
    }

    private static final Logger log = LoggerFactory.getLogger(Thumbnailer.class);
    /** 200 × 200 inches: anything larger is not a real page. */
    private static final float MAX_PAGE_POINTS = 200 * 72;

    private final WorkerProperties.Thumbnail settings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    Thumbnailer(WorkerProperties properties) {
        this.settings = properties.thumbnail();
    }

    Result fromPdf(byte[] pdf) {
        if (pdf.length > settings.maxInputMb() * 1024 * 1024) {
            return new Result(Optional.empty(), null);
        }
        return withTimeout(() -> {
            try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdf))) {
                int pages = document.getNumberOfPages();
                if (pages == 0) {
                    return new Result(Optional.empty(), 0);
                }
                PDRectangle box = document.getPage(0).getCropBox();
                float longest = Math.max(box.getWidth(), box.getHeight());
                if (longest <= 0 || longest > MAX_PAGE_POINTS) {
                    return new Result(Optional.empty(), pages);
                }
                PDFRenderer renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                BufferedImage image = renderer.renderImage(0, settings.size() / longest, ImageType.RGB);
                return new Result(Optional.of(png(image)), pages);
            }
        }, "PDF");
    }

    Result fromImage(byte[] bytes) {
        return withTimeout(() -> {
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
                if (readers == null || !readers.hasNext()) {
                    return new Result(Optional.empty(), 1);
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    long width = reader.getWidth(0);
                    long height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width * height > settings.maxPixels()) {
                        return new Result(Optional.empty(), 1);
                    }
                    ImageReadParam param = reader.getDefaultReadParam();
                    int step = (int) Math.max(1, Math.max(width, height) / settings.size());
                    param.setSourceSubsampling(step, step, 0, 0);
                    return new Result(Optional.of(png(scale(reader.read(0, param)))), 1);
                } finally {
                    reader.dispose();
                }
            }
        }, "image");
    }

    private interface Work {
        Result run() throws Exception;
    }

    private Result withTimeout(Work work, String what) {
        Future<Result> future = executor.submit(work::run);
        try {
            return future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.info("No {} thumbnail: rendering took longer than {}", what, settings.timeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("No {} thumbnail: {}", what, e.getCause() == null ? e.toString() : e.getCause().toString());
        }
        return new Result(Optional.empty(), null);
    }

    private BufferedImage scale(BufferedImage source) {
        double factor = (double) settings.size() / Math.max(source.getWidth(), source.getHeight());
        if (factor >= 1) {
            return source;
        }
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
