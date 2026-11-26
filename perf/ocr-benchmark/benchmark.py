"""OCR benchmark (B-13, O-02): Tesseract (through the JDMS Tika server, as the worker calls it) against PaddleOCR on
the synthetic pages from samples.py. Reports character error rate, word recall, field extraction accuracy and time per
page, per engine, language and quality, as JSON and Markdown.

Engines:
  tesseract-eng+ara  Tika + Tesseract with the worker's setting (X-Tika-OCRLanguage: eng+ara) for every page
  tesseract-lang     Tika + Tesseract with the page's own language only (eng or ara)
  paddle             PaddleOCR 3 (PP-OCRv5 mobile detection; Arabic: arabic_PP-OCRv5_mobile_rec, English: the default
                     recogniser), CPU, in-process

Usage: python benchmark.py <samples-dir> <out-dir> [--tika http://localhost:9998] [--engines a,b,c]
"""
import argparse
import json
import os
import platform
import re
import statistics
import time
import unicodedata
import urllib.request
from collections import Counter
from pathlib import Path

from rapidfuzz.distance import Levenshtein

ARABIC_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
DIACRITICS = re.compile(r"[ً-ْٰـ]")  # harakat, superscript alef, tatweel
BIDI = re.compile(r"[‎‏‪-‮⁦-⁩﻿]")
ALEF = str.maketrans("أإآ", "ااا")


def normalise(text):
    """What a search index would see: NFKC, Western digits, no diacritics or bidi marks, one alef, lower case."""
    text = unicodedata.normalize("NFKC", text)
    text = BIDI.sub("", text).translate(ARABIC_DIGITS)
    text = DIACRITICS.sub("", text).translate(ALEF).lower()
    return re.sub(r"\s+", " ", text).strip()


def words(text):
    return re.findall(r"\w+", normalise(text))


def tesseract(tika, languages):
    def run(image_path, page):
        request = urllib.request.Request(tika + "/tika", data=image_path.read_bytes(), method="PUT", headers={
            "Content-Type": "image/png", "Accept": "text/plain; charset=UTF-8",
            "X-Tika-OCRLanguage": languages or ("ara" if page["language"] == "ar" else "eng")})
        with urllib.request.urlopen(request, timeout=300) as response:
            return response.read().decode("utf-8")
    return run


def paddle():
    from paddleocr import PaddleOCR
    models = {}

    def run(image_path, page):
        language = page["language"]
        if language not in models:
            # CPU settings that work on a small machine: oneDNN off (PaddlePaddle 3.3's oneDNN path fails on these
            # models), the mobile detection model on pages scaled to at most 2000 px (the server model needs more than
            # 6 GB on a 300 dpi page), one text line per recognition batch, and the Arabic recognition model named
            # explicitly (with a detection model named, "ar" otherwise falls back to a recogniser without Arabic)
            options = dict(lang=language, use_doc_orientation_classify=False, use_doc_unwarping=False,
                           use_textline_orientation=False, enable_mkldnn=False,
                           text_detection_model_name="PP-OCRv5_mobile_det", text_det_limit_type="max",
                           text_det_limit_side_len=2000, text_recognition_batch_size=1)
            if language == "ar":
                options["text_recognition_model_name"] = "arabic_PP-OCRv5_mobile_rec"
            models[language] = PaddleOCR(**options)
        result = models[language].predict(str(image_path))[0]
        return lines_in_reading_order(result["rec_texts"], result["rec_boxes"], language == "ar")
    return run


def lines_in_reading_order(texts, boxes, rtl):
    """Groups recognised boxes into lines (by vertical overlap), then orders them top to bottom and by direction."""
    items = sorted(({"text": t, "x0": float(b[0]), "y0": float(b[1]), "x1": float(b[2]), "y1": float(b[3])}
                    for t, b in zip(texts, boxes)), key=lambda i: (i["y0"] + i["y1"]) / 2)
    lines = []
    for item in items:
        middle = (item["y0"] + item["y1"]) / 2
        if lines and abs(middle - lines[-1]["middle"]) < (item["y1"] - item["y0"]) * 0.5:
            lines[-1]["items"].append(item)
        else:
            lines.append({"middle": middle, "items": [item]})
    return "\n".join(" ".join(i["text"] for i in sorted(line["items"], key=lambda i: i["x0"], reverse=rtl))
                     for line in lines)


def score(page, text):
    truth = normalise(page["text"])
    found = normalise(text)
    cer = Levenshtein.distance(truth, found) / max(1, len(truth))
    truth_words = Counter(words(page["text"]))
    found_words = Counter(words(text))
    recall = sum((truth_words & found_words).values()) / max(1, sum(truth_words.values()))
    fields = {name: normalise(value) in found for name, value in page["fields"].items()}
    return {"cer": cer, "word_recall": recall, "fields": fields}


def summarise(rows):
    groups = {}
    for row in rows:
        for key in ((row["engine"], "all", "all"), (row["engine"], row["language"], "all"),
                    (row["engine"], row["language"], row["quality"])):
            groups.setdefault(key, []).append(row)
    summary = []
    for (engine, language, quality), members in sorted(groups.items()):
        fields = [ok for m in members for ok in m["fields"].values()]
        seconds = [m["seconds"] for m in members]
        summary.append({
            "engine": engine, "language": language, "quality": quality, "pages": len(members),
            "cer_mean": statistics.fmean(m["cer"] for m in members),
            "cer_median": statistics.median(m["cer"] for m in members),
            "word_recall": statistics.fmean(m["word_recall"] for m in members),
            "field_accuracy": sum(fields) / max(1, len(fields)),
            "seconds_per_page_mean": statistics.fmean(seconds),
            "seconds_per_page_p95": sorted(seconds)[max(0, round(0.95 * len(seconds)) - 1)],
        })
    return summary


def markdown(summary, environment):
    out = ["# OCR benchmark results", "",
           f"Run {environment['finished']} on {environment['machine']}; {environment['pages']} pages per engine. "
           "CER: character error rate after normalisation (lower is better). Word recall: share of ground-truth "
           "words found. Field accuracy: share of expected field values (names, identifiers, account numbers, "
           "amounts, dates) found exactly. Time: wall clock per page, one page at a time.", "",
           "| Engine | Language | Quality | Pages | CER mean | CER median | Word recall | Field accuracy | s/page mean "
           "| s/page p95 |", "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for s in summary:
        out.append(f"| {s['engine']} | {s['language']} | {s['quality']} | {s['pages']} | {s['cer_mean']:.1%} | "
                   f"{s['cer_median']:.1%} | {s['word_recall']:.1%} | {s['field_accuracy']:.1%} | "
                   f"{s['seconds_per_page_mean']:.2f} | {s['seconds_per_page_p95']:.2f} |")
    return "\n".join(out) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("samples")
    parser.add_argument("out")
    parser.add_argument("--tika", default="http://localhost:9998")
    parser.add_argument("--engines", default="tesseract-eng+ara,tesseract-lang,paddle")
    args = parser.parse_args()
    samples = Path(args.samples)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    pages = json.loads((samples / "manifest.json").read_text(encoding="utf-8"))
    engines = {"tesseract-eng+ara": lambda: tesseract(args.tika, "eng+ara"),
               "tesseract-lang": lambda: tesseract(args.tika, None), "paddle": paddle}
    rows = []
    environment = {}

    def write():
        environment.update({"finished": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "pages": len(pages),
                            "machine": f"{platform.machine()}, {os.cpu_count()} CPUs (shared with the stack)"})
        summary = summarise(rows)
        (out / "results.json").write_text(json.dumps({"environment": environment, "summary": summary,
                                                     "pages": rows}, ensure_ascii=False, indent=1), encoding="utf-8")
        (out / "results.md").write_text(markdown(summary, environment), encoding="utf-8")

    for name in args.engines.split(","):
        run = engines[name]()
        run(samples / f"{pages[0]['id']}.png", pages[0])  # warm-up (model load, JIT), not measured
        for page in pages:
            started = time.perf_counter()
            text = run(samples / f"{page['id']}.png", page)
            seconds = time.perf_counter() - started
            (out / "text").mkdir(exist_ok=True)
            (out / "text" / f"{name}-{page['id']}.txt").write_text(text, encoding="utf-8")
            row = {"engine": name, "page": page["id"], "language": page["language"], "quality": page["quality"],
                   "template": page["template"], "seconds": seconds, **score(page, text)}
            rows.append(row)
            print(f"{name} {page['id']} cer={row['cer']:.3f} fields={sum(row['fields'].values())}/"
                  f"{len(row['fields'])} {seconds:.2f}s")
        write()  # after each engine: a later failure keeps what was measured


if __name__ == "__main__":
    main()
