#!/usr/bin/env bash
# Builds the user manual, testing guide, installation guide, workflow setup guide, FLEXCUBE integration guide and executive briefing in docs/manuals.
# Word has no page numbers until a document is laid out, so the guides are built twice: pass 1 is
# rendered to PDF text (LibreOffice in the doc-render image) to find the page of every heading, and
# pass 2 writes those page numbers into the table of contents.
#
# Usage: docs/manuals/build/build.sh [screenshot-dir]   (default: docs/manuals/screenshots)
# Needs Node 24, Python 3 and Docker (run as a member of the docker group, or under: sg docker -c ...).
set -euo pipefail

BUILD="$(cd "$(dirname "$0")" && pwd)"
MANUALS="$(dirname "$BUILD")"
SHOTS="$(cd "${1:-$MANUALS/screenshots}" && pwd)"
IMAGE=jdms-doc-render:1
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$BUILD"
[ -d node_modules ] || npm ci --no-audit --no-fund
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -q -t "$IMAGE" render

GUIDES=(
  "JDMS-Next-User-Manual manual.js"
  "JDMS-Next-Testing-Guide testing.js"
  "JDMS-Next-Installation-Guide install.js"
  "JDMS-Next-Workflow-Setup-Guide workflow.js"
  "JDMS-Next-FLEXCUBE-Integration-Guide integration.js"
)

render_text() {
  rm -f "$WORK"/render/*; mkdir -p "$WORK/render"; cp "$MANUALS"/*.docx "$WORK/render/"
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$WORK/render:/work" "$IMAGE" sh -c \
    'soffice -env:UserInstallation=file:///tmp/lo --headless --convert-to pdf --outdir /work /work/*.docx >/dev/null 2>&1
     for f in *.pdf; do pdftotext -layout "$f" "${f%.pdf}.txt"; done'
}

for pass in 1 2; do
  for guide in "${GUIDES[@]}"; do
    set -- $guide
    [ "$pass" = 1 ] && rm -f "$WORK/toc-$1.json"
    TOC_PAGES="$WORK/toc-$1.json" node "$2" "$MANUALS/$1.docx" "$SHOTS" >/dev/null
  done
  [ "$pass" = 2 ] && break
  render_text
  for guide in "${GUIDES[@]}"; do
    set -- $guide
    python3 pages.py "$WORK/render/$1.txt" "$2" > "$WORK/toc-$1.json"
  done
done

node deck.js "$MANUALS/JDMS-Next-Executive-Briefing.pptx" "$SHOTS"
ls -l "$MANUALS"/*.docx "$MANUALS"/*.pptx
