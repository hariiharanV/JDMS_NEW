# Apache Tika server with Tesseract Arabic in addition to the languages of the -full image (spec §4.2: OCR in
# English and Arabic). Used by docker-compose and by the worker's integration tests.
FROM apache/tika:3.3.3.0-full
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr-ara \
    && rm -rf /var/lib/apt/lists/*
USER 35002:35002
