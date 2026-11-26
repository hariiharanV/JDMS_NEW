"""Synthetic banking pages for the OCR benchmark (B-13; O-02 default: synthetic generated documents).

Each page is HTML rendered to PDF by Gotenberg (Chromium shapes Arabic correctly), rasterised with pdftoppm and then
degraded to three qualities: clean (300 dpi), scan (200 dpi, slight skew, blur, noise) and poor (150 dpi, more skew,
blur, noise, JPEG artefacts). The ground truth is the text put on the page, line by line, plus the fields a bank would
extract (name, identifiers, account, amount, date).

Usage: python samples.py <out-dir> [--gotenberg http://localhost:3300] [--per-template 2] [--seed 2026]
"""
import argparse
import html
import io
import json
import random
import re
import subprocess
import tempfile
import urllib.request
import uuid
from pathlib import Path

from PIL import Image, ImageFilter
import numpy as np

FIRST_EN = ["Mohammed", "Fatma", "Salim", "Aisha", "Khalid", "Maryam", "Rashid", "Huda", "Yousuf", "Layla"]
LAST_EN = ["Al Balushi", "Al Harthy", "Al Busaidi", "Al Hinai", "Al Rawahi", "Al Kindi", "Al Farsi", "Al Amri"]
FIRST_AR = ["محمد", "فاطمة", "سالم", "عائشة", "خالد", "مريم", "راشد", "هدى", "يوسف", "ليلى"]
LAST_AR = ["البلوشي", "الحارثي", "البوسعيدي", "الهنائي", "الرواحي", "الكندي", "الفارسي", "العامري"]
EMPLOYERS_EN = ["Muscat Municipality", "Sohar Aluminium", "Royal Hospital", "National Ferries", "Gulf Engineering"]
EMPLOYERS_AR = ["بلدية مسقط", "صحار للألمنيوم", "المستشفى السلطاني", "العبارات الوطنية", "الخليج للهندسة"]
DESCRIPTIONS_EN = ["Salary credit", "ATM withdrawal", "Card purchase", "Transfer to savings", "Utility bill",
                   "Cheque deposit", "Loan instalment", "Bank charges"]
DESCRIPTIONS_AR = ["إيداع راتب", "سحب نقدي", "شراء بالبطاقة", "تحويل إلى التوفير", "فاتورة كهرباء", "إيداع شيك",
                   "قسط قرض", "رسوم بنكية"]

LATIN_RUN = re.compile(r"[+\-]?[A-Za-z0-9][A-Za-z0-9.\-/]*")

QUALITIES = {
    "clean": dict(dpi=300, angle=0.0, blur=0.0, noise=0, jpeg=None),
    "scan": dict(dpi=200, angle=0.8, blur=0.6, noise=10, jpeg=None),
    "poor": dict(dpi=150, angle=-1.8, blur=0.9, noise=20, jpeg=35),
}


def person(rng, arabic):
    if arabic:
        return f"{rng.choice(FIRST_AR)} {rng.choice(FIRST_AR)} {rng.choice(LAST_AR)}"
    return f"{rng.choice(FIRST_EN)} {rng.choice(LAST_EN)}"


def data(rng, arabic):
    return {
        "name": person(rng, arabic),
        "cif": f"C{rng.randrange(10**7, 10**8):08d}",
        "account": f"{rng.choice(['001', '002', '007'])}{rng.randrange(10**9, 10**10)}",
        "civil_id": f"{rng.randrange(10**7, 10**8)}",
        "passport": f"P{rng.randrange(10**6, 10**7)}",
        "amount": f"{rng.randrange(300, 9000)}.{rng.randrange(0, 1000):03d}",
        "date": f"2026-{rng.randrange(1, 13):02d}-{rng.randrange(1, 29):02d}",
        "employer": rng.choice(EMPLOYERS_AR if arabic else EMPLOYERS_EN),
        "loan": f"LN{rng.randrange(10**8, 10**9)}",
        "months": str(rng.choice([12, 24, 36, 48, 60, 84, 120])),
    }


def salary(d, ar):
    if ar:
        lines = ["شهادة راتب", f"التاريخ: {d['date']}", "إلى من يهمه الأمر",
                 f"نشهد بأن السيد {d['name']} يعمل لدى {d['employer']}",
                 f"الرقم المدني: {d['civil_id']}", f"الراتب الشهري: {d['amount']} ريال عماني",
                 f"رقم الحساب: {d['account']}", "أعطيت هذه الشهادة بناء على طلبه دون أدنى مسؤولية"]
    else:
        lines = ["SALARY CERTIFICATE", f"Date: {d['date']}", "To whom it may concern",
                 f"This is to certify that {d['name']} is employed by {d['employer']}",
                 f"Civil ID: {d['civil_id']}", f"Monthly salary: OMR {d['amount']}",
                 f"Account number: {d['account']}", "This certificate is issued at the request of the employee"]
    return lines, {"name": d["name"], "civil_id": d["civil_id"], "amount": d["amount"], "account": d["account"],
                   "date": d["date"]}


def statement(d, ar, rng):
    descriptions = DESCRIPTIONS_AR if ar else DESCRIPTIONS_EN
    head = (["كشف حساب", f"العميل: {d['name']}", f"رقم العميل: {d['cif']}", f"رقم الحساب: {d['account']}",
             f"تاريخ الكشف: {d['date']}"] if ar else
            ["ACCOUNT STATEMENT", f"Customer: {d['name']}", f"CIF: {d['cif']}", f"Account: {d['account']}",
             f"Statement date: {d['date']}"])
    rows = []
    balance = rng.randrange(1000, 5000)
    for i in range(8):
        change = rng.randrange(-400, 900)
        balance += change
        rows.append(f"2026-01-{i * 3 + 2:02d} {rng.choice(descriptions)} {change:+d}.000 {balance}.000")
    closing = f"{balance}.000"
    tail = [f"الرصيد الختامي: {closing}"] if ar else [f"Closing balance: {closing}"]
    return head + rows + tail, {"name": d["name"], "cif": d["cif"], "account": d["account"], "amount": closing,
                                "date": d["date"]}


def loan(d, ar):
    if ar:
        lines = [f"اتفاقية قرض رقم {d['loan']}", f"المقترض: {d['name']}", f"رقم العميل: {d['cif']}",
                 f"مبلغ القرض: {d['amount']}0 ريال عماني", f"مدة القرض: {d['months']} شهراً",
                 f"تاريخ الاتفاقية: {d['date']}",
                 "يتعهد المقترض بسداد الأقساط الشهرية في موعدها",
                 "يحق للبنك المطالبة بكامل المبلغ عند التأخر في السداد"]
    else:
        lines = [f"LOAN AGREEMENT No. {d['loan']}", f"Borrower: {d['name']}", f"CIF: {d['cif']}",
                 f"Loan amount: OMR {d['amount']}0", f"Tenor: {d['months']} months", f"Agreement date: {d['date']}",
                 "The borrower shall pay each monthly instalment on its due date",
                 "The bank may demand the full amount if any instalment is overdue"]
    return lines, {"name": d["name"], "loan": d["loan"], "cif": d["cif"], "amount": d["amount"] + "0",
                   "date": d["date"]}


def kyc(d, ar):
    if ar:
        lines = ["نموذج التحقق من الهوية", f"الاسم الكامل: {d['name']}", "الجنسية: عماني",
                 f"رقم جواز السفر: {d['passport']}", f"الرقم المدني: {d['civil_id']}",
                 f"تاريخ الانتهاء: {d['date']}", f"رقم العميل: {d['cif']}", "تمت مطابقة الصورة مع حامل الوثيقة"]
    else:
        lines = ["IDENTITY VERIFICATION FORM", f"Full name: {d['name']}", "Nationality: Omani",
                 f"Passport number: {d['passport']}", f"Civil ID: {d['civil_id']}", f"Expiry date: {d['date']}",
                 f"CIF: {d['cif']}", "The photograph was matched with the holder of the document"]
    return lines, {"name": d["name"], "passport": d["passport"], "civil_id": d["civil_id"], "cif": d["cif"],
                   "date": d["date"]}


def page_html(lines, ar):
    direction = "rtl" if ar else "ltr"
    font = "'Noto Naskh Arabic', 'Noto Sans Arabic', 'DejaVu Sans', sans-serif" if ar else \
        "'Liberation Serif', 'DejaVu Serif', serif"
    def cell(text):
        escaped = html.escape(text)
        # numbers and Latin runs keep their own order inside Arabic lines, as banks print them (dates stay dates)
        return LATIN_RUN.sub(lambda m: f'<bdi dir="ltr">{m.group(0)}</bdi>', escaped) if ar else escaped
    body = "\n".join(f"<h1>{cell(lines[0])}</h1>" if i == 0 else f"<p>{cell(line)}</p>"
                     for i, line in enumerate(lines))
    return f"""<!doctype html><html lang="{'ar' if ar else 'en'}" dir="{direction}"><head><meta charset="utf-8">
<style>@page {{ size: A4; margin: 22mm }} body {{ font-family: {font}; font-size: 14pt; color: #111 }}
h1 {{ font-size: 20pt; margin: 0 0 14pt }} p {{ margin: 0 0 9pt; unicode-bidi: plaintext }}</style></head>
<body>{body}</body></html>"""


def render_pdf(gotenberg, document):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"index.html\"\r\n"
            f"Content-Type: text/html\r\n\r\n").encode() + document.encode("utf-8") + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(gotenberg + "/forms/chromium/convert/html", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def rasterise(pdf, dpi):
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "page.pdf"
        source.write_bytes(pdf)
        subprocess.run(["pdftoppm", "-r", str(dpi), "-png", "-gray", "-singlefile", "-f", "1", "-l", "1",
                        str(source), str(Path(tmp) / "page")], check=True)
        return Image.open(Path(tmp) / "page.png").convert("L")


def degrade(image, quality, rng):
    q = QUALITIES[quality]
    if q["angle"]:
        image = image.rotate(q["angle"], resample=Image.BICUBIC, expand=True, fillcolor=255)
    if q["blur"]:
        image = image.filter(ImageFilter.GaussianBlur(q["blur"]))
    if q["noise"]:
        pixels = np.asarray(image, dtype=np.float32)
        noise = np.random.default_rng(rng.randrange(2**32)).normal(0, q["noise"], pixels.shape)
        image = Image.fromarray(np.clip(pixels + noise, 0, 255).astype(np.uint8))
    if q["jpeg"]:
        buffer = io.BytesIO()
        image.save(buffer, "JPEG", quality=q["jpeg"])
        image = Image.open(io.BytesIO(buffer.getvalue())).convert("L")
    return image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--gotenberg", default="http://localhost:3300")
    parser.add_argument("--per-template", type=int, default=2)
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)
    manifest = []
    for ar in (False, True):
        for template in ("salary", "statement", "loan", "kyc"):
            for n in range(args.per_template):
                d = data(rng, ar)
                lines, fields = {"salary": lambda: salary(d, ar), "statement": lambda: statement(d, ar, rng),
                                 "loan": lambda: loan(d, ar), "kyc": lambda: kyc(d, ar)}[template]()
                pdf = render_pdf(args.gotenberg, page_html(lines, ar))
                for quality, settings in QUALITIES.items():
                    page_id = f"{'ar' if ar else 'en'}-{template}-{n + 1}-{quality}"
                    image = degrade(rasterise(pdf, settings["dpi"]), quality, rng)
                    image.save(out / f"{page_id}.png", dpi=(settings["dpi"], settings["dpi"]))
                    manifest.append({"id": page_id, "language": "ar" if ar else "en", "template": template,
                                     "quality": quality, "dpi": settings["dpi"], "text": "\n".join(lines),
                                     "fields": fields})
                    print(page_id)
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
