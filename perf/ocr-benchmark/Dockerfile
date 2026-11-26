# OCR benchmark harness (B-13, O-02): PaddleOCR in-process, Tesseract through the JDMS Tika container, synthetic
# banking pages rendered by Gotenberg. Benchmark tooling only; never deployed. Run with --network host and --memory 6g
# (docs/perf/README.md).
FROM python:3.12.14-slim-bookworm
RUN apt-get update \
    && apt-get install -y --no-install-recommends poppler-utils libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home bench
USER bench
WORKDIR /home/bench
COPY --chown=bench requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt
ENV PATH=/home/bench/.local/bin:$PATH PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
# downloads the English and Arabic models once, at build time (no network needed while measuring)
RUN python -c "from paddleocr import PaddleOCR; [PaddleOCR(lang=l, use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False, enable_mkldnn=False, text_detection_model_name='PP-OCRv5_mobile_det', **({'text_recognition_model_name': 'arabic_PP-OCRv5_mobile_rec'} if l == 'ar' else {})) for l in ('en', 'ar')]"
COPY --chown=bench *.py ./
ENTRYPOINT ["python", "-u"]
