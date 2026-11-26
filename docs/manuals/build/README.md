# Building the manuals

The six documents in `docs/manuals` are generated; edit the scripts here, not the Word or
PowerPoint files.

| Output | Script |
|---|---|
| `JDMS-Next-User-Manual.docx` | `manual.js` |
| `JDMS-Next-Testing-Guide.docx` | `testing.js` |
| `JDMS-Next-Installation-Guide.docx` | `install.js` |
| `JDMS-Next-FLEXCUBE-Integration-Guide.docx` | `integration.js` (with `docs/api/jdms-integration-v1.openapi.yaml`) |
| `JDMS-Next-Workflow-Setup-Guide.docx` | `workflow.js` (screenshots 40, 41, 43, 45: `frontend/e2e/manual/workflow-guide.spec.ts`, read-only) |
| `JDMS-Next-Executive-Briefing.pptx` | `deck.js` (theme colours written by `theme.js`) |

`lib.js` holds the shared Word layout (cover, contents, headings, tables, figures, header and
footer). Libraries: docx, pptxgenjs, jszip, react-icons (Material Design icons only, Apache-2.0),
sharp; all MIT or Apache-2.0.

## Build

```bash
docs/manuals/build/build.sh            # needs Node 24, Python 3 and Docker
```

The script installs the npm packages, builds the `render/` image (LibreOffice and Poppler) on
first use, and builds each guide twice: the first build is laid out by LibreOffice to find the
page of every heading, the second writes those pages into the table of contents. It takes
about 20 seconds.

## Screenshots

The figures come from `docs/manuals/screenshots`. To take them again after the screens change,
start the local stack (compose, core and worker, as for `npm run e2e:stack`), then:

```bash
set -a; . deploy/.env; set +a
cd frontend && SHOTS=../docs/manuals/screenshots npm run screenshots:manual
```

The run signs in as the dev users and, in tenant BANK1, uploads three passports, approves two,
deletes one, places a legal hold and releases it again, and proposes a document type that a
second administrator rejects; so run it against a local database only. On this build machine add
`JDMS_E2E_CHROMIUM_ARGS="--disable-gpu --disable-software-rasterizer"` (headless Chromium hangs on
screenshots without it). A step that fails is reported as `STEP FAILED …` and the run goes on, so
check the output, then rebuild the documents and look through them: figure captions and the
steps around them describe what the screenshots show.

## Test data for a fresh test server

A new installation has the dev users, document types and lists of values, but no documents. To
give testers something to work with:

```bash
set -a; . deploy/.env; set +a
cd frontend && npm run seed:test-data
```

It signs in as the dev users and, through the API, adds ten customers of tenant BANK1 with 27
passports, national ID cards, account opening forms, loan agreements and collateral deeds:
maker1 uploads those of branch 001 and the integration client those of branch 002; checker1
approves them and the credit committee the large home loan. Two documents of Noura Al Kindi stay
in checker1's tasks, Omar Al Saadi's passport is rejected, and records1 places a legal hold on
Salim Al Harthy (CIF 100245). A second run finds the first customer and adds nothing. It runs
against `JDMS_WEB_PUBLIC_URL`; without `JDMS_INTEGRATION_TEST_CLIENT_SECRET` it leaves out
branch 002.
