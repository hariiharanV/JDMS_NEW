package com.jmr.jdms.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures a logger's events and renders them with the application's console encoder (the structured JSON format).
 * Independent of {@code System.out}, which Logback binds once per JVM, so it is reliable across test classes.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type, Level level) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.previousLevel = logger.getLevel();
        logger.setLevel(level);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type, Level level) {
        return new LogCapture(type, level);
    }

    /** Captured events as the console would print them. */
    @SuppressWarnings("unchecked")
    public List<String> lines() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Encoder<ILoggingEvent> encoder = ((OutputStreamAppender<ILoggingEvent>) root.iteratorForAppenders().next())
                .getEncoder();
        return List.copyOf(appender.list).stream()
                .map(event -> new String(encoder.encode(event), StandardCharsets.UTF_8).strip()).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }
}
