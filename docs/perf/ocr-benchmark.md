# OCR benchmark: Tesseract and PaddleOCR, English and Arabic (B-13, O-02)

Run on 2026-10-25 on the 2-vCPU machine of [results.md](results.md), one page at a time. Harness:
`perf/ocr-benchmark` (ADR-0021). Full numbers: [ocr/results.md](ocr/results.md) (per engine, language and quality) and
[ocr/results.json](ocr/results.json) (per page, with the fields found).

## Sample set (O-02 default: synthetic)

48 pages: 4 banking templates (salary certificate, account statement with a transaction table, loan agreement, identity
verification form), 2 instances each, in English and Arabic, each at three qualities:

| Quality | How it is made |
| --- | --- |
| clean | rendered by Gotenberg (Chromium, so Arabic is shaped as in a real document), rasterised at 300 dpi |
| scan | 200 dpi, 0.8° skew, slight blur, noise |
| poor | 150 dpi, 1.8° skew, more blur and noise, JPEG artefacts |

Arabic pages carry Western digits and Latin identifiers (CIF `C…`, passport `P…`, loan `LN…`) inside right-to-left
lines, as Omani bank documents do. Examples: [English statement, poor](ocr/sample-en-statement-1-poor.jpg),
[Arabic salary certificate, scan](ocr/sample-ar-salary-1-scan.jpg). Ground truth is the text put on the page and the
fields a bank would extract: names, civil ID, CIF, account, passport and loan numbers, amounts, dates
([ocr/samples-manifest.json](ocr/samples-manifest.json)).

## Engines

| Engine | Configuration |
| --- | --- |
| tesseract-eng+ara | Tesseract through the JDMS Tika server, exactly as the worker calls it (`X-Tika-OCRLanguage: eng+ara`) |
| tesseract-lang | the same with only the page's language (`eng` or `ara`) |
| paddle | PaddleOCR 3.7 / PaddlePaddle 3.3 on CPU: PP-OCRv5 mobile detection, pages scaled to at most 2,000 px; recognition `arabic_PP-OCRv5_mobile_rec` for Arabic, the default recogniser for English; oneDNN off |

PaddleOCR's defaults did not run here: its oneDNN path fails on PaddlePaddle 3.3 ("ConvertPirAttribute2RuntimeAttribute
not support"), the server detection model took more than 12 GB on a 300 dpi page (killed by the kernel; more than 6 GB
even with pages scaled down), and with a detection model named, `lang="ar"` silently used a recogniser without Arabic
(blank output). The configuration above is the one that works on a CPU machine of this size.

## Results

| Engine | Language | CER mean | Word recall | Field accuracy | Seconds per page (mean, p95) |
| --- | --- | ---: | ---: | ---: | --- |
| tesseract-eng+ara | English | 0.2% | 99.6% | 97.5% | 1.9, 3.1 |
| tesseract-eng+ara | Arabic | 5.1% | 93.5% | 80.8% | 2.1, 3.3 |
| tesseract-lang | English | 0.1% | 99.8% | 97.5% | 1.8, 2.5 |
| tesseract-lang | Arabic | 5.5% | 91.3% | 70.0% | 1.6, 2.5 |
| paddle | English | 0.0% | 100.0% | 100.0% | 12.4, 16.3 |
| paddle | Arabic | 35.7% | 53.4% | 17.5% | 10.1, 13.7 |

CER: character error rate after normalising (Unicode NFKC, Western digits, no diacritics or bidi marks, one alef form),
in reading order. Field accuracy: share of expected values found exactly in the text. Quality made little difference
at these levels; per-quality rows are in [ocr/results.md](ocr/results.md).

Arabic fields missed (of the pages that carry them):

| Field | tesseract-eng+ara | tesseract-lang (ara) | paddle |
| --- | --- | --- | --- |
| Name | 4 / 24 | 4 / 24 | 4 / 24 |
| CIF (`C` + 8 digits) | 14 / 18 | 18 / 18 | 18 / 18 |
| Passport (`P` + 7 digits) | 3 / 6 | 6 / 6 | 6 / 6 |
| Loan number (`LN` + 9 digits) | 0 / 6 | 6 / 6 | 6 / 6 |
| Civil ID, account | 0 / 24 | 1 / 24 | 24 / 24 |
| Amount | 2 / 18 | 1 / 18 | 17 / 18 |
| Date | 0 / 24 | 0 / 24 | 24 / 24 |

## Conclusions

1. **Keep Tesseract (`eng+ara`) as the OCR engine.** Equal on English, far better on Arabic, five to six times
   faster on CPU, a few hundred MB of memory, already inside the Tika server. `eng+ara` beats Arabic alone on Arabic
   pages because those pages carry Latin identifiers: the worker's setting is right.
2. **PaddleOCR is not a fit as configured for this hardware.** Perfect English, but its Arabic recogniser drops or
   garbles the Western digits inside Arabic lines, which are exactly the values a bank extracts (dates, amounts,
   accounts). It also needs a large machine (or a GPU) and careful configuration. Re-test only with a GPU, the server
   recognition models and the bank's real samples (O-02); adopting it would need its own ADR.
3. **Mixed identifiers in Arabic lines are the weak spot for Tesseract.** A CIF such as `C25112594` inside an Arabic
   line comes back with the letter moved and misread (`25112594©`). Field extraction for such identifiers should match
   the digits and tolerate the letter's position, or read a known zone of the form; not needed in the PoC (fields come
   from metadata, OCR feeds search).
4. **Content search is robust to these errors:** names, account numbers and words are found (word recall above 90%
   in Arabic); in the ingest load test, 41 of 43 scans were found by a random 12-letter word in their content and the
   other 2 had one letter misread.

Limits: synthetic pages and 48 pages per engine; real scans (stamps, signatures, handwriting, photographed IDs,
colour backgrounds) will be harder for every engine. The bank sample set (O-02) is needed before a final choice.
