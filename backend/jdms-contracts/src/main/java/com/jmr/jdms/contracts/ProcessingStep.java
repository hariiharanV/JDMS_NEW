package com.jmr.jdms.contracts;

/** Steps of the processing pipeline run by the worker, in order: SCAN → EXTRACT → (OCR) → RENDER. */
public enum ProcessingStep {
    SCAN, EXTRACT, OCR, RENDER
}
