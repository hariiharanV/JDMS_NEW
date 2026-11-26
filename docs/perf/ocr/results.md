# OCR benchmark results

Run 2026-10-25T18:30:54Z on x86_64, 2 CPUs (shared with the stack); 48 pages per engine. CER: character error rate after normalisation (lower is better). Word recall: share of ground-truth words found. Field accuracy: share of expected field values (names, identifiers, account numbers, amounts, dates) found exactly. Time: wall clock per page, one page at a time.

| Engine | Language | Quality | Pages | CER mean | CER median | Word recall | Field accuracy | s/page mean | s/page p95 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| paddle | all | all | 48 | 17.9% | 9.0% | 76.7% | 58.8% | 11.22 | 16.05 |
| paddle | ar | all | 24 | 35.7% | 25.1% | 53.4% | 17.5% | 10.05 | 13.72 |
| paddle | ar | clean | 8 | 27.8% | 21.7% | 64.3% | 22.5% | 11.25 | 14.29 |
| paddle | ar | poor | 8 | 46.3% | 44.4% | 38.9% | 12.5% | 7.94 | 9.77 |
| paddle | ar | scan | 8 | 33.1% | 23.3% | 57.1% | 17.5% | 10.97 | 13.72 |
| paddle | en | all | 24 | 0.0% | 0.0% | 100.0% | 100.0% | 12.39 | 16.27 |
| paddle | en | clean | 8 | 0.0% | 0.0% | 100.0% | 100.0% | 13.96 | 17.91 |
| paddle | en | poor | 8 | 0.0% | 0.0% | 100.0% | 100.0% | 10.06 | 13.55 |
| paddle | en | scan | 8 | 0.0% | 0.0% | 100.0% | 100.0% | 13.15 | 16.05 |
| tesseract-eng+ara | all | all | 48 | 2.7% | 0.4% | 96.5% | 89.2% | 2.03 | 3.27 |
| tesseract-eng+ara | ar | all | 24 | 5.1% | 4.9% | 93.5% | 80.8% | 2.14 | 3.31 |
| tesseract-eng+ara | ar | clean | 8 | 6.7% | 8.0% | 93.0% | 85.0% | 2.32 | 3.69 |
| tesseract-eng+ara | ar | poor | 8 | 2.8% | 2.0% | 94.7% | 77.5% | 1.99 | 3.15 |
| tesseract-eng+ara | ar | scan | 8 | 5.9% | 6.6% | 92.6% | 80.0% | 2.10 | 3.31 |
| tesseract-eng+ara | en | all | 24 | 0.2% | 0.0% | 99.6% | 97.5% | 1.92 | 3.10 |
| tesseract-eng+ara | en | clean | 8 | 0.0% | 0.0% | 100.0% | 100.0% | 2.11 | 2.92 |
| tesseract-eng+ara | en | poor | 8 | 0.5% | 0.2% | 99.1% | 95.0% | 1.85 | 3.22 |
| tesseract-eng+ara | en | scan | 8 | 0.0% | 0.0% | 99.7% | 97.5% | 1.81 | 2.49 |
| tesseract-lang | all | all | 48 | 2.8% | 0.3% | 95.6% | 83.8% | 1.68 | 2.50 |
| tesseract-lang | ar | all | 24 | 5.5% | 4.8% | 91.3% | 70.0% | 1.59 | 2.50 |
| tesseract-lang | ar | clean | 8 | 6.7% | 9.0% | 90.9% | 70.0% | 1.72 | 2.67 |
| tesseract-lang | ar | poor | 8 | 3.4% | 2.6% | 92.4% | 70.0% | 1.41 | 2.35 |
| tesseract-lang | ar | scan | 8 | 6.5% | 8.2% | 90.7% | 70.0% | 1.63 | 2.50 |
| tesseract-lang | en | all | 24 | 0.1% | 0.0% | 99.8% | 97.5% | 1.78 | 2.50 |
| tesseract-lang | en | clean | 8 | 0.0% | 0.0% | 100.0% | 100.0% | 2.08 | 2.88 |
| tesseract-lang | en | poor | 8 | 0.2% | 0.1% | 99.6% | 95.0% | 1.49 | 2.23 |
| tesseract-lang | en | scan | 8 | 0.0% | 0.0% | 99.7% | 97.5% | 1.77 | 2.17 |
