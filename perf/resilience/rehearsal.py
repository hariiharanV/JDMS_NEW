"""Resilience rehearsal against the running local stack (B-13): kill the worker mid-job, restart OpenSearch, take the
object store away. Each scenario uploads through the API as perfmaker1 (tenant PERF), breaks something for real
(SIGKILL, docker restart, docker stop) and then checks that nothing was lost and every status is right: each upload
exists exactly once, every version ends DONE, no job is DEAD or FAILED, every document is searchable by metadata and
content. Timings and outcomes are written as JSON and Markdown.

Usage (from the repository root, compose, core and worker running):
  set -a; . deploy/.env; set +a
  python3 perf/resilience/rehearsal.py <out-dir> [--scenarios worker,opensearch,s3]
"""
import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from pathlib import Path

CORE = os.environ.get("JDMS_CORE", "http://localhost:8080")
KEYCLOAK = os.environ.get("JDMS_KEYCLOAK", "http://localhost:8180") + "/realms/jdms-local/protocol/openid-connect"
REDIRECT = "http://localhost:5180/callback"
ROOT = Path(__file__).resolve().parents[2]
WORKER = ROOT / "perf" / "resilience" / "worker.sh"
POSTGRES = os.environ.get("JDMS_POSTGRES_CONTAINER", "jdms-postgres-1")
OPENSEARCH = os.environ.get("JDMS_OPENSEARCH_CONTAINER", "jdms-opensearch-1")
SEAWEEDFS = os.environ.get("JDMS_SEAWEEDFS_CONTAINER", "jdms-seaweedfs-1")
USER = "perfmaker1"


# ---- access token (authorization code + PKCE through the login form, as the web client does)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


_token = {"value": None, "expires": 0.0}
_token_lock = threading.Lock()


def token():
    with _token_lock:
        if _token["value"] is None or _token["expires"] - time.time() < 60:
            _token["value"], lifetime = login(USER)
            _token["expires"] = time.time() + lifetime
        return _token["value"]


def login(username):
    opener = urllib.request.build_opener(NoRedirect)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    query = urllib.parse.urlencode({"client_id": "jdms-web", "response_type": "code", "scope": "openid",
                                    "redirect_uri": REDIRECT, "code_challenge": challenge,
                                    "code_challenge_method": "S256", "state": secrets.token_hex(6)})
    with opener.open(f"{KEYCLOAK}/auth?{query}") as page:
        cookies = "; ".join(c.split(";", 1)[0] for c in page.headers.get_all("Set-Cookie") or [])
        html = page.read().decode()
    action = re.search(r'action="([^"]+)"', html).group(1).replace("&amp;", "&")
    form = urllib.parse.urlencode({"username": username, "password": os.environ["JDMS_DEV_USER_PASSWORD"]}).encode()
    request = urllib.request.Request(action, data=form, headers={"Cookie": cookies})
    try:
        opener.open(request)
        raise RuntimeError(f"login of {username} did not redirect")
    except urllib.error.HTTPError as redirect:
        location = redirect.headers["Location"]
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)["code"][0]
    body = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": "jdms-web",
                                   "redirect_uri": REDIRECT, "code": code, "code_verifier": verifier}).encode()
    with urllib.request.urlopen(f"{KEYCLOAK}/token", data=body) as response:
        answer = json.load(response)
    return answer["access_token"], answer.get("expires_in", 300)


# ---- API

def api(method, path, body=None, headers=None, timeout=60):
    request = urllib.request.Request(CORE + path, data=body, method=method,
                                     headers={"Authorization": "Bearer " + token(), **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, {"raw": text[:200]}
    except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
        return 0, {"error": str(error)}


def pdf(marker, pages=3):
    """A small digital PDF; the marker word is on every page (the content check searches for it)."""
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    kids = []
    for page in range(pages):
        text = f"BT /F1 12 Tf 60 780 Td (Resilience rehearsal {marker} page {page + 1}) Tj 0 -20 Td " \
               f"(Signatures were compared with the specimen held on file by the branch.) Tj ET"
        objects.append(f"<< /Length {len(text)} >>\nstream\n{text}\nendstream")
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> "
                       f">> /Contents {len(objects)} 0 R >>")
        kids.append(f"{len(objects)} 0 R")
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {pages} >>"
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n{body}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += "".join(f"{offset:010d} 00000 n \n" for offset in offsets).encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def scanned_pdf(seed, width=1654, height=2339):
    """An image-only PDF page (a 200 dpi greyscale scan of noise and bars): no text layer, so the worker runs OCR,
    which takes seconds; long enough for a crash to land in the middle of the step."""
    pixels = bytearray()
    state = seed & 0x7FFFFFFF or 1
    for y in range(height):
        for x in range(0, width, 8):
            state = (state * 1103515245 + 12345) & 0x7FFFFFFF
            value = 30 if (y // 40) % 3 == 0 and (x // 120) % 2 == 0 else 200 + (state >> 16) % 55
            pixels += bytes([value]) * min(8, width - x)
    image = zlib.compress(bytes(pixels), 6)
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im1 4 0 R >> >> "
               "/Contents 5 0 R >>",
               None, None]
    content = "q 595 0 0 842 0 0 cm /Im1 Do Q"
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        if number == 4:
            out += (f"4 0 obj\n<< /Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace "
                    f"/DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length {len(image)} >>\nstream\n").encode()
            out += image + b"\nendstream\nendobj\n"
        elif number == 5:
            out += f"5 0 obj\n<< /Length {len(content)} >>\nstream\n{content}\nendstream\nendobj\n".encode()
        else:
            out += f"{number} 0 obj\n{body}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += "".join(f"{offset:010d} 00000 n \n" for offset in offsets).encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def new_scan(tag):
    item = new_upload(tag)
    item.update({"file": scanned_pdf(secrets.randbits(31)), "scanned": True})
    return item


def new_upload(tag, pages=3):
    marker = "zr" + "".join(secrets.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(10))
    return {"tag": tag, "marker": marker, "cif": f"R{secrets.randbelow(10**11):011d}", "key": str(uuid.uuid4()),
            "file": pdf(marker, pages)}


def upload(item):
    """One upload attempt with the item's own Idempotency-Key (a retry sends the same key)."""
    metadata = json.dumps({"documentTypeCode": "ACCOUNT_OPENING_FORM", "title": f"Resilience {item['cif']}",
                           "branchCode": "001", "cif": item["cif"], "accountNo": "A" + item["cif"][1:],
                           "customFields": {"account_type": "SAVINGS", "date_opened": "2026-10-01"}})
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json"
            f"\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"form.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").encode() + item["file"] + \
        f"\r\n--{boundary}--\r\n".encode()
    status, answer = api("POST", "/api/v1/documents", body, {
        "Content-Type": f"multipart/form-data; boundary={boundary}", "Idempotency-Key": item["key"]})
    if status == 201:
        item["id"] = answer["id"]
    return status, (answer or {}).get("code")


def upload_with_retries(item, every=5.0, limit=300.0):
    started = time.time()
    attempts = []
    while time.time() - started < limit:
        status, code = upload(item)
        attempts.append(status if status != 503 else f"503 {code}")
        if status == 201:
            break
        time.sleep(every)
    item["attempts"] = attempts
    return item


def search(body):
    return api("POST", "/api/v1/search", json.dumps(body).encode(), {"Content-Type": "application/json"})


def found_by_cif(item):
    status, answer = search({"filters": {"cif": item["cif"]}})
    return status == 200 and answer["total"]


def found_by_content(item):
    status, answer = search({"query": item["marker"], "filters": {"cif": item["cif"]}})
    return status == 200 and answer["total"] == 1


def processing(item):
    status, versions = api("GET", f"/api/v1/documents/{item['id']}/versions")
    return versions[0]["processingStatus"] if status == 200 else None


def wait_until(check, limit, every=2.0):
    started = time.time()
    while time.time() - started < limit:
        if check():
            return time.time() - started
        time.sleep(every)
    return None


# ---- database checks (the schema owner, through psql in the compose container; values passed as psql variables)

def sql(query, **variables):
    command = ["docker", "exec", "-i", "-e", f"PGPASSWORD={os.environ['JDMS_DB_OWNER_PASSWORD']}", POSTGRES, "psql",
               "-U", os.environ["JDMS_DB_OWNER_USERNAME"], "-d", os.environ.get("JDMS_DB_NAME", "jdms"), "-At",
               "-v", "ON_ERROR_STOP=1"]
    for name, value in variables.items():
        command += ["-v", f"{name}={value}"]
    result = subprocess.run(command, input=query, capture_output=True, text=True, check=True)
    return [line.split("|") for line in result.stdout.strip().splitlines() if line]


def job_summary(items):
    ids = ",".join(item["id"] for item in items if "id" in item)
    rows = sql("""
        select j.type, j.status, count(*), max(j.attempts) from job j
         where j.payload ->> 'documentId' = any (string_to_array(:'ids', ','))
         group by 1, 2 order by 1, 2;
        """, ids=ids)
    return [{"type": r[0], "status": r[1], "count": int(r[2]), "max_attempts": int(r[3])} for r in rows]


def documents_per_cif(items):
    cifs = ",".join(item["cif"] for item in items)
    rows = sql("select cif, count(*) from document where cif = any (string_to_array(:'cifs', ',')) group by 1;",
               cifs=cifs)
    return {r[0]: int(r[1]) for r in rows}


def verify(items, limit=900):
    """Every upload exists once, is processed (DONE), and is searchable by metadata and content."""
    counts = documents_per_cif(items)
    took = wait_until(lambda: all(processing(item) == "DONE" for item in items if "id" in item), limit, 5)
    searchable = wait_until(lambda: all(found_by_cif(item) == 1 and (item.get("scanned") or found_by_content(item))
                                        for item in items if "id" in item), 300, 3)
    jobs = job_summary(items)
    return {
        "uploads": len(items),
        "created": sum(1 for item in items if "id" in item),
        "exactly_once": all(counts.get(item["cif"]) == 1 for item in items),
        "all_processed_s": took,
        "all_searchable_s": searchable,
        "jobs": jobs,
        "dead_or_failed": sum(j["count"] for j in jobs if j["status"] in ("DEAD", "FAILED")),
    }


# ---- scenarios

WORKER_JOBS = ("SCAN", "EXTRACT", "OCR", "RENDER")


def worker_crash(log_dir):
    # digital documents and image-only scans (OCR takes seconds: long enough for the crash to land mid-step)
    items = [new_upload("worker", pages=40) if i % 2 else new_scan("worker") for i in range(30)]
    uploading = threading.Thread(target=lambda: [upload_with_retries(item) for item in items])
    uploading.start()
    running = []

    def worker_step_running():
        running[:] = [j for j in job_summary(items) if j["status"] == "RUNNING" and j["type"] in WORKER_JOBS]
        return bool(running)

    # crash the worker in the middle of this batch: as soon as one of its steps is seen running
    in_flight = wait_until(worker_step_running, 180, 0.2)
    subprocess.run([str(WORKER), "kill"], check=True)
    killed_at = time.time()
    orphaned = sum(j["count"] for j in running)
    uploading.join()  # uploads go on: the core does not need the worker
    time.sleep(5)
    subprocess.run([str(WORKER), "start", str(log_dir / "worker-after-crash.log")], check=True)
    verify_started = time.time()
    result = verify(items)
    result.update({"killed_after_s": in_flight, "worker_steps_running_when_killed": orphaned,
                   "all_processed_after_kill_s": None if result["all_processed_s"] is None
                   else round(verify_started - killed_at + result["all_processed_s"], 1)})
    return result


def opensearch_restart(log_dir):
    items = [new_upload("opensearch") for _ in range(20)]
    statuses, search_codes = [], []

    def uploads():
        for item in items:
            statuses.append(upload(item)[0])
            time.sleep(1)

    thread = threading.Thread(target=uploads)
    thread.start()
    time.sleep(5)
    restarted = time.time()
    subprocess.Popen(["docker", "restart", OPENSEARCH])
    while thread.is_alive():
        status, answer = search({"query": "rehearsal"})
        search_codes.append(status if status == 200 else f"{status} {(answer or {}).get('code')}")
        time.sleep(1)
    thread.join()
    wait_until(lambda: subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", OPENSEARCH],
                                      capture_output=True, text=True).stdout.strip() == "healthy", 300)
    healthy_after = round(time.time() - restarted, 1)
    result = verify(items)
    result.update({"upload_statuses": sorted(set(map(str, statuses))),
                   "search_during_restart": sorted(set(map(str, search_codes))),
                   "opensearch_healthy_after_s": healthy_after})
    return result


def object_store_outage(log_dir):
    before = [new_upload("s3-before") for _ in range(5)]
    for item in before:
        upload_with_retries(item)
    during = [new_upload("s3-during") for _ in range(10)]
    subprocess.run(["docker", "stop", SEAWEEDFS], check=True, capture_output=True)
    stopped = time.time()
    threads = [threading.Thread(target=upload_with_retries, args=(item,)) for item in during]
    for thread in threads:
        thread.start()
        time.sleep(0.5)
    time.sleep(max(0.0, 30 - (time.time() - stopped)))
    subprocess.run(["docker", "start", SEAWEEDFS], check=True, capture_output=True)
    outage = time.time() - stopped
    for thread in threads:
        thread.join()
    result = verify(before + during)
    result.update({"outage_s": round(outage, 1),
                   "attempts_during_outage": [item["attempts"] for item in during],
                   "first_answer_during_outage": sorted({str(item["attempts"][0]) for item in during})})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--scenarios", default="worker,opensearch,s3")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    scenarios = {"worker": worker_crash, "opensearch": opensearch_restart, "s3": object_store_outage}
    results = {}
    for name in args.scenarios.split(","):
        started = time.time()
        print(f"scenario {name} ...", flush=True)
        results[name] = scenarios[name](out)
        results[name]["duration_s"] = round(time.time() - started, 1)
        print(json.dumps(results[name], indent=1), flush=True)
    (out / "resilience.json").write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
