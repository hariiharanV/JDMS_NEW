const pptxgen = require('pptxgenjs');
const React = require('react');
const ReactDOMServer = require('react-dom/server');
const sharp = require('sharp');
const md = require('react-icons/md');
const fs = require('fs');
const { applyTheme } = require('./theme');

const OUT = process.argv[2];
const SHOTS = process.argv[3];

const THEME = {
  name: 'JDMS Next', headFontFace: 'Cambria', bodyFontFace: 'Calibri',
  colors: { dk1: '1E2933', lt1: 'FFFFFF', dk2: '163A63', lt2: 'EEF2F5', accent1: '2357A6', accent2: 'B08423',
    accent3: '2F7D4F', accent4: 'B3261E', accent5: '56657A', accent6: '9A6200', hlink: '2357A6', folHlink: '163A63' },
};
const HEX = { lapis: '163A63', blue: '2357A6', brass: 'B08423', mist: 'EEF2F5', graphite: '1E2933', slate: '56657A',
  green: '2F7D4F', red: 'B3261E', rule: 'D3DAE2', white: 'FFFFFF', ice: 'DCE5F0' };

async function icon(Comp, color, size = 256) {
  const svg = ReactDOMServer.renderToStaticMarkup(React.createElement(Comp, { color: '#' + color, size: String(size) }));
  const png = await sharp(Buffer.from(svg)).png().toBuffer();
  return 'image/png;base64,' + png.toString('base64');
}

(async () => {
  const pres = new pptxgen();
  pres.layout = 'LAYOUT_WIDE'; // 13.33 x 7.5
  pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
  pres.title = 'JDMS Next executive briefing';
  pres.author = 'JDMS Next project';
  pres.company = 'JMR';
  const C = pres.SchemeColor;

  // ---- layouts
  pres.defineSlideMaster({
    title: 'TITLE', background: { color: HEX.lapis },
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: 0.8, y: 2.3, w: 11.5, h: 1.4, fontSize: 48, bold: true,
        color: C.background1, fontFace: THEME.headFontFace, valign: 'bottom', align: 'left', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'body', type: 'body', x: 0.8, y: 3.8, w: 11.5, h: 1.2, fontSize: 22,
        color: 'DCE5F0', valign: 'top', align: 'left', margin: 0 }, text: '' } },
    ],
  });
  pres.defineSlideMaster({
    title: 'CONTENT', background: { color: HEX.white },
    margin: [0.5, 0.6, 0.6, 0.6],
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: 0.6, y: 0.35, w: 12.1, h: 0.9, fontSize: 32, bold: true,
        color: C.text2, fontFace: THEME.headFontFace, valign: 'middle', margin: 0 }, text: '' } },
      { text: { text: 'JDMS Next  |  Executive briefing  |  October 2026', options: { x: 0.6, y: 7.05, w: 8, h: 0.3,
        fontSize: 10, color: C.accent5, margin: 0 } } },
    ],
    slideNumber: { x: 12.2, y: 7.05, w: 0.5, h: 0.3, fontSize: 10, color: HEX.slate, align: 'right' },
  });
  pres.defineSlideMaster({
    title: 'DARK', background: { color: HEX.lapis },
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', x: 0.6, y: 0.35, w: 12.1, h: 0.9, fontSize: 32, bold: true,
        color: C.background1, fontFace: THEME.headFontFace, valign: 'middle', margin: 0 }, text: '' } },
    ],
    slideNumber: { x: 12.2, y: 7.05, w: 0.5, h: 0.3, fontSize: 10, color: 'DCE5F0', align: 'right' },
  });

  const I = {
    shield: await icon(md.MdShield, HEX.white), lock: await icon(md.MdLock, HEX.white), check: await icon(md.MdDoneAll, HEX.white),
    search: await icon(md.MdSearch, HEX.white), lang: await icon(md.MdTranslate, HEX.white), cogs: await icon(md.MdSettings, HEX.white),
    archive: await icon(md.MdArchive, HEX.white), user: await icon(md.MdVerifiedUser, HEX.white), plug: await icon(md.MdPower, HEX.white),
    chart: await icon(md.MdShowChart, HEX.white), history: await icon(md.MdHistory, HEX.white), db: await icon(md.MdStorage, HEX.white),
    exchange: await icon(md.MdSwapHoriz, HEX.white), building: await icon(md.MdAccountBalance, HEX.white), bolt: await icon(md.MdBolt, HEX.white),
    hourglass: await icon(md.MdHourglassEmpty, HEX.white), warn: await icon(md.MdWarning, HEX.white),
    server: await icon(md.MdDns, HEX.white), scan: await icon(md.MdDescription, HEX.white), coins: await icon(md.MdSavings, HEX.white),
    users: await icon(md.MdGroups, HEX.white), redo: await icon(md.MdRefresh, HEX.white), flag: await icon(md.MdFlag, HEX.white),
  };
  // icon in a coloured circle
  function badge(slide, img, x, y, d, color, name) {
    slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color }, line: { color }, objectName: name + ' circle' });
    slide.addImage({ data: img, x: x + d * 0.25, y: y + d * 0.25, w: d * 0.5, h: d * 0.5, objectName: name + ' icon' });
  }
  function text(slide, value, opts) {
    slide.addText(value, { isTextBox: true, margin: 0, valign: 'top', color: C.text1, fontSize: 14, ...opts });
  }

  // 1. Title
  pres.addSection({ title: 'Opening' });
  let s = pres.addSlide({ masterName: 'TITLE', sectionTitle: 'Opening' });
  s.addText('JDMS Next', { placeholder: 'title' });
  s.addText('Secure, bilingual document management for banks: what it brings compared with today\'s JDMS', { placeholder: 'body' });
  text(s, [{ text: 'JDMS', options: { color: HEX.brass, bold: true } }, { text: ' Next', options: { color: HEX.white } }],
    { x: 0.8, y: 0.6, w: 4, h: 0.5, fontSize: 24, fontFace: 'Cambria', objectName: 'wordmark' });
  text(s, 'Executive briefing  |  Proof of concept results  |  October 2026', { x: 0.8, y: 6.5, w: 10, h: 0.4, fontSize: 14,
    color: 'DCE5F0', objectName: 'date line' });
  s.addNotes('Purpose: decide whether JDMS Next moves from proof of concept to a pilot bank. The deck compares today\'s JDMS with JDMS Next, shows what was built and measured, and lists the decisions needed.');

  // 2. The message
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Opening' });
  s.addText('JDMS Next is built, measured and ready for a pilot', { placeholder: 'title' });
  text(s, 'The proof of concept replaced every core JDMS function on a modern, open-source platform, closed the security gaps of the legacy system, and proved itself on one million documents.',
    { x: 0.6, y: 1.5, w: 4.6, h: 3, fontSize: 20, color: C.text2, fontFace: 'Cambria', italic: true, objectName: 'key message' });
  const msgs = [
    [I.check, HEX.blue, 'Complete', '14 of 15 planned work items done; about 480 automated tests run on every change'],
    [I.shield, HEX.lapis, 'Secure by design', 'Per-file encryption, single sign-on, access checked on every screen, search and download'],
    [I.chart, HEX.green, 'Proven at scale', '1,000,062 documents: search in 0.7 s, previews in under 0.6 s'],
    [I.exchange, HEX.brass, 'Migration ready', 'A tool moves legacy JDMS documents with versions, approvals and permissions, and proves it'],
  ];
  msgs.forEach(([img, col, head, body], i) => {
    const y = 1.45 + i * 1.32;
    badge(s, img, 5.8, y, 0.8, col, head);
    text(s, head, { x: 6.85, y: y + 0.02, w: 5.8, h: 0.4, fontSize: 18, bold: true, color: C.text2, objectName: head + ' title' });
    text(s, body, { x: 6.85, y: y + 0.42, w: 5.8, h: 0.75, fontSize: 14, color: C.text1, objectName: head + ' text' });
  });
  s.addNotes('Four messages. Complete: the PoC backlog is done except the optional Oracle smoke run (deferred by the product owner). Secure: the legacy security defects are designed out. Proven: measured on a single shared 2-CPU machine, so production hardware will do better. Migration: the tool and its reconciliation report were rehearsed on synthetic legacy data.');

  // 3. Why change
  pres.addSection({ title: 'Why change' });
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Why change' });
  s.addText('Today\'s JDMS has reached the end of what it can safely carry', { placeholder: 'title' });
  const cols = [
    [I.hourglass, HEX.slate, 'Technology at end of life', ['Java 8, Spring 4, Hibernate 4 on JBoss or WebLogic', 'Server-built pages, no Arabic right-to-left design', 'Workflow rules compiled at run time', 'Hard to test, scale or host']],
    [I.warn, HEX.red, 'Security gaps found in review', ['One hard-coded encryption key for all files (AES, ECB mode)', 'Plain-text passwords in configuration; decrypted temporary files on disk', 'Search index holds document text in clear', 'External API trusts the user and role IDs callers send']],
    [I.users, HEX.brass, 'What banks now ask for', ['Maker-checker and committee approvals with deadlines', 'Records management: retention, legal holds, controlled destruction', 'Searchable scans in English and Arabic', 'Proof of who saw and changed what']],
  ];
  cols.forEach(([img, col, head, items], i) => {
    const x = 0.6 + i * 4.15;
    s.addShape(pres.shapes.RECTANGLE, { x, y: 1.5, w: 3.85, h: 5.2, fill: { color: HEX.mist }, line: { color: HEX.mist }, objectName: head + ' card' });
    badge(s, img, x + 0.3, 1.8, 0.75, col, head);
    text(s, head, { x: x + 0.3, y: 2.7, w: 3.3, h: 0.6, fontSize: 18, bold: true, color: C.text2, objectName: head + ' heading' });
    text(s, items.map((t, j) => ({ text: t, options: { bullet: true, breakLine: j < items.length - 1 } })),
      { x: x + 0.3, y: 3.4, w: 3.3, h: 3.1, fontSize: 14, paraSpaceAfter: 8, objectName: head + ' list' });
  });
  s.addNotes('Source: the legacy code review (docs/06-legacy-jdms-reference.md). These defects cannot be patched without rewriting the core; every one of them is designed out of JDMS Next.');

  // 4. Comparison
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Why change' });
  s.addText('Side by side: current JDMS and JDMS Next', { placeholder: 'title' });
  const head = (t) => ({ text: t, options: { bold: true, color: HEX.white, fill: { color: HEX.lapis }, fontSize: 14 } });
  const row = (a, b, c) => [{ text: a, options: { bold: true, color: HEX.lapis } }, { text: b, options: { color: HEX.slate } }, { text: c }];
  s.addTable([
    [head('Area'), head('Current JDMS'), head('JDMS Next')],
    row('Platform', 'Java 8, Spring 4, application server', 'Java 25, Spring Boot 4, modular core and worker, containers'),
    row('File security', 'One hard-coded key for every file', 'A key per file, wrapped by a key per bank (AES-256-GCM)'),
    row('Sign-in and integration', 'Own login; trusts IDs sent by callers', 'Single sign-on (Keycloak, AD/LDAP); OAuth2 for bank systems'),
    row('Access control', 'Screen flags and folder permissions', 'Role, branch and clearance rules on every path, including search'),
    row('Approvals', 'Custom workflow tables', 'Maker-checker, multi-stage and committee approval with deadlines'),
    row('Search and scans', 'Plain-text index, no OCR', 'Arabic and English search of content and scans, filtered by access'),
    row('Records and audit', 'Recycle bin; history table', 'Retention, legal holds, disposition; tamper-evident audit trail'),
  ], { x: 0.6, y: 1.45, w: 12.1, colW: [2.4, 4.0, 5.7], fontSize: 14, fontFace: 'Calibri', color: HEX.graphite,
    border: { type: 'solid', pt: 0.75, color: HEX.rule }, rowH: 0.62, valign: 'middle', margin: [0.04, 0.1, 0.04, 0.1],
    objectName: 'comparison table' });
  s.addNotes('Read across: every row is a capability the bank either lacks today or has only in an unsafe form. Further rows not shown: multi-bank tenant isolation proven by tests; antivirus, OCR and PDF previews for every upload; English and Arabic screens.');

  // 5. What JDMS Next brings
  pres.addSection({ title: 'What JDMS Next brings' });
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'What JDMS Next brings' });
  s.addText('What JDMS Next brings to the bank', { placeholder: 'title' });
  const feats = [
    [I.scan, HEX.blue, 'Smart upload', 'Forms adapt to each document type; required details checked before anything is stored'],
    [I.user, HEX.lapis, 'Approvals', 'Inbox with maker-checker, multi-stage and committee votes; deadlines and full history'],
    [I.search, HEX.green, 'Search inside documents', 'Words, CIF and filters; OCR of scans in English and Arabic'],
    [I.shield, HEX.red, 'Right people only', 'Role, branch and clearance on every screen, search, preview and download'],
    [I.archive, HEX.brass, 'Records management', 'Retention policies, legal holds, controlled destruction by two officers'],
    [I.history, HEX.slate, 'Tamper-evident audit', 'Every action and refusal recorded in a hash chain nobody can alter'],
    [I.plug, HEX.blue, 'Integration API', 'Secure API for FLEXCUBE and other bank systems, with safe retries'],
    [I.lang, HEX.lapis, 'Bilingual', 'Every screen in English and Arabic, right to left, keyboard accessible'],
  ];
  feats.forEach(([img, col, h, b], i) => {
    const x = 0.6 + (i % 4) * 3.07; const y = 1.5 + Math.floor(i / 4) * 2.75;
    badge(s, img, x, y, 0.75, col, h);
    text(s, h, { x, y: y + 0.9, w: 2.8, h: 0.45, fontSize: 17, bold: true, color: C.text2, objectName: h + ' title' });
    text(s, b, { x, y: y + 1.35, w: 2.8, h: 1.2, fontSize: 14, objectName: h + ' text' });
  });
  s.addNotes('The eight capabilities a branch user, checker, records officer or auditor will notice first. All are built and tested in the PoC; the User Manual shows each screen.');

  // 6. Architecture
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'What JDMS Next brings' });
  s.addText('A modern, open architecture', { placeholder: 'title' });
  const box = (label, sub, x, y, w, h, fill, name, color = HEX.white) => {
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { color: fill }, objectName: name });
    text(s, [{ text: label, options: { bold: true, fontSize: 15, color, breakLine: !!sub } }, ...(sub ? [{ text: sub, options: { fontSize: 12, color } }] : [])],
      { x: x + 0.1, y: y + 0.05, w: w - 0.2, h: h - 0.1, align: 'center', valign: 'middle', objectName: name + ' label' });
  };
  const arrow = (x1, y1, x2, y2, name) => s.addShape(pres.shapes.LINE, { x: Math.min(x1, x2), y: Math.min(y1, y2),
    w: Math.abs(x2 - x1) || 0.001, h: Math.abs(y2 - y1) || 0.001, flipV: y2 < y1, line: { color: HEX.slate, width: 1.5, endArrowType: 'triangle' }, objectName: name });
  box('Bank staff', 'Web screens, English and Arabic', 0.6, 1.6, 2.6, 1.0, HEX.blue, 'users box');
  box('Bank systems', 'FLEXCUBE and others (API)', 0.6, 3.0, 2.6, 1.0, HEX.blue, 'systems box');
  box('Keycloak', 'Single sign-on, AD/LDAP', 0.6, 4.6, 2.6, 1.0, HEX.slate, 'keycloak box');
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 4.0, y: 1.6, w: 4.6, h: 4.0, rectRadius: 0.08, fill: { color: HEX.lapis }, line: { color: HEX.lapis }, objectName: 'core box' });
  text(s, 'JDMS Next core', { x: 4.1, y: 1.68, w: 4.4, h: 0.4, fontSize: 15, bold: true, color: HEX.white, align: 'center', objectName: 'core label' });
  ['Documents and versions', 'Metadata catalog', 'Access policy', 'Approvals (Flowable)', 'Search queries', 'Records', 'Audit trail', 'Integration API']
    .forEach((m, i) => box(m, '', 4.2 + (i % 2) * 2.15, 2.15 + Math.floor(i / 2) * 0.82, 2.05, 0.68, HEX.ice, 'module ' + m, HEX.lapis));
  box('PostgreSQL', 'Data, workflow, audit, job queue', 9.6, 1.6, 3.1, 0.9, HEX.green, 'db box');
  box('OpenSearch', 'Arabic and English search', 9.6, 2.7, 3.1, 0.9, HEX.green, 'search box');
  box('S3 object store', 'Encrypted files', 9.6, 3.8, 3.1, 0.9, HEX.green, 'store box');
  box('Worker', 'Antivirus, text, OCR, previews', 9.6, 4.9, 3.1, 0.9, HEX.brass, 'worker box');
  arrow(3.2, 2.1, 4.0, 2.1, 'arrow users'); arrow(3.2, 3.5, 4.0, 3.5, 'arrow systems'); arrow(3.2, 5.1, 4.0, 5.1, 'arrow keycloak');
  arrow(8.6, 2.05, 9.6, 2.05, 'arrow db'); arrow(8.6, 3.15, 9.6, 3.15, 'arrow search'); arrow(8.6, 4.25, 9.6, 4.25, 'arrow store');
  arrow(8.6, 5.35, 9.6, 5.35, 'arrow worker');
  text(s, 'All components are open source under permissive licences; PostgreSQL now, Oracle supported before the pilot bank.',
    { x: 0.6, y: 6.15, w: 12.1, h: 0.5, fontSize: 14, color: C.accent5, italic: true, objectName: 'architecture note' });
  s.addNotes('One modular core application and one processing worker. No message broker is needed: a transactional outbox and a database job queue carry the work. Every box can scale or be replaced behind its interface (for example the KMS key service or the object store).');

  // 7. Security
  s = pres.addSlide({ masterName: 'DARK', sectionTitle: 'What JDMS Next brings' });
  s.addText('Security designed in, and proven by tests', { placeholder: 'title' });
  const sec = [
    [I.lock, 'Encryption per file', 'Each file has its own key, wrapped by the bank\'s key; nothing is ever decrypted to disk'],
    [I.user, 'Identity from the bank', 'Single sign-on; bank systems use OAuth2 clients, never trusted user IDs'],
    [I.shield, 'Access on every path', 'Screens, search results, totals, facets, previews, downloads and exports'],
    [I.check, 'Two people for changes', 'Approvals, administration and access rules all need a second person'],
    [I.history, 'Tamper-evident audit', 'Hash-chained, verified daily; the application itself cannot alter it'],
    [I.building, 'Banks kept apart', 'Tenant isolation on every row, file, index and job, proven by tests'],
  ];
  sec.forEach(([img, h, b], i) => {
    const x = 0.6 + (i % 3) * 4.15; const y = 1.55 + Math.floor(i / 3) * 2.6;
    badge(s, img, x, y, 0.75, HEX.brass, h);
    text(s, h, { x, y: y + 0.9, w: 3.8, h: 0.45, fontSize: 18, bold: true, color: HEX.white, objectName: h + ' title' });
    text(s, b, { x, y: y + 1.35, w: 3.8, h: 1.0, fontSize: 14, color: HEX.ice, objectName: h + ' text' });
  });
  s.addNotes('Each claim is backed by automated tests that run on every change: an authorization test (allowed and denied) for every endpoint, a tenant-isolation suite, a search leakage suite, and audit-chain verification.');

  // 8. Measured results
  pres.addSection({ title: 'Evidence' });
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Evidence' });
  s.addText('Measured on one million documents', { placeholder: 'title' });
  const stats = [
    ['1,000,062', 'documents in the test bank, English and Arabic'],
    ['0.7 s', 'search response (95% of searches) at 5 per second'],
    ['0.12 s', 'first-page preview under load (95%)'],
    ['7 s', 'until an uploaded PDF\'s content is searchable (95%)'],
    ['Immediate', 'removal of access after a deny rule is approved'],
    ['54 min', 'to rebuild the whole search index from the database'],
  ];
  stats.forEach(([v, l], i) => {
    const x = 0.6 + (i % 3) * 4.15; const y = 1.55 + Math.floor(i / 3) * 2.45;
    s.addShape(pres.shapes.RECTANGLE, { x, y, w: 3.85, h: 2.15, fill: { color: HEX.mist }, line: { color: HEX.mist }, objectName: 'stat card ' + i });
    text(s, v, { x: x + 0.25, y: y + 0.25, w: 3.4, h: 0.95, fontSize: 44, bold: true, color: C.accent1, fontFace: 'Cambria', objectName: 'stat value ' + i });
    text(s, l, { x: x + 0.25, y: y + 1.25, w: 3.4, h: 0.75, fontSize: 14, objectName: 'stat label ' + i });
  });
  text(s, 'Everything ran on a single shared 2-CPU machine, so these are lower bounds; production hardware will be faster.',
    { x: 0.6, y: 6.5, w: 12.1, h: 0.4, fontSize: 13, italic: true, color: C.accent5, objectName: 'stats caveat' });
  s.addNotes('Details in docs/perf/results.md. Search saturates around 10 searches per second on this machine; dedicated search nodes move that limit. Bulk ingestion: one worker processed about 2,760 documents per hour; 100,000 per hour needs about 72 CPUs of processing capacity.');

  // 9. Targets chart
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Evidence' });
  s.addText('Every response-time target met with room to spare', { placeholder: 'title' });
  s.addChart(pres.charts.BAR, [{ name: 'Share of target used', labels: ['Search (1.5 s target)', 'First-page preview (2 s)', 'Content searchable, digital (2 min)', 'Content searchable, scans (10 min)', 'Permission revocation (60 s)'],
    values: [47, 29, 6, 3, 1] }], {
    x: 0.6, y: 1.45, w: 7.6, h: 5.2, barDir: 'bar', chartColors: [HEX.blue], showValue: true, dataLabelPosition: 'outEnd',
    dataLabelFormatCode: '0"%"', dataLabelFontSize: 13, dataLabelColor: HEX.graphite, dataLabelFontFace: '+mn-lt',
    catAxisLabelFontSize: 13, catAxisLabelColor: HEX.graphite, catAxisLabelFontFace: '+mn-lt', catAxisOrientation: 'maxMin',
    valAxisMaxVal: 100, valAxisMinVal: 0, valAxisLabelFormatCode: '0"%"', valAxisLabelFontSize: 11, valAxisLabelColor: HEX.slate,
    valAxisLabelFontFace: '+mn-lt', valGridLine: { color: 'E3E8EE', size: 0.5 }, catGridLine: { style: 'none' }, showLegend: false,
    showTitle: true, title: 'Measured value as a share of the target (lower is better)', titleFontSize: 14, titleColor: HEX.lapis,
    titleFontFace: '+mn-lt', objectName: 'targets chart' });
  const facts = [['Search', '705 ms at 5/s'], ['Preview', '583 ms during uploads'], ['Digital content', '7.1 s'], ['Scans with OCR', '17.6 s'], ['Revocation', 'under 0.4 s']];
  text(s, 'Measured (95th percentile)', { x: 8.7, y: 1.55, w: 4, h: 0.4, fontSize: 16, bold: true, color: C.text2, objectName: 'facts heading' });
  facts.forEach(([a, b], i) => {
    text(s, [{ text: a + ': ', options: { bold: true } }, { text: b }], { x: 8.7, y: 2.1 + i * 0.62, w: 4, h: 0.5, fontSize: 15, objectName: 'fact ' + i });
  });
  text(s, 'Targets from the PoC specification, section 7, pending confirmation (O-06).', { x: 8.7, y: 5.4, w: 4, h: 0.8, fontSize: 13,
    italic: true, color: C.accent5, objectName: 'targets note' });
  s.addNotes('The bar shows how much of each target the measurement used: search used 47% of its 1.5-second budget. Metadata becomes searchable within about 1 to 2 seconds of upload (near-real-time), which the spec wording "immediately" should reflect.');

  // 10. Resilience
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Evidence' });
  s.addText('It keeps working when parts fail, and recovers what it holds', { placeholder: 'title' });
  const res = [
    [I.server, HEX.blue, 'Processing server killed mid-task', 'Work taken over in about 2 minutes; every document processed exactly once'],
    [I.search, HEX.green, 'Search engine restarted', 'Uploads never failed; everything searchable again within minutes'],
    [I.db, HEX.brass, 'File storage offline for 30 s', 'Users told to retry; no document lost or stored twice'],
    [I.redo, HEX.lapis, 'Full backup and restore', '1 million documents restored and verified: counts, files, keys, audit chain'],
  ];
  res.forEach(([img, col, h, b], i) => {
    const y = 1.5 + i * 1.3;
    badge(s, img, 0.6, y, 0.8, col, h);
    text(s, h, { x: 1.65, y, w: 5.4, h: 0.45, fontSize: 18, bold: true, color: C.text2, objectName: h + ' title' });
    text(s, b, { x: 1.65, y: y + 0.45, w: 5.4, h: 0.7, fontSize: 14, objectName: h + ' text' });
  });
  s.addShape(pres.shapes.RECTANGLE, { x: 7.6, y: 1.5, w: 5.1, h: 5.1, fill: { color: HEX.mist }, line: { color: HEX.mist }, objectName: 'findings card' });
  text(s, 'Weaknesses the tests found, now fixed', { x: 7.9, y: 1.75, w: 4.6, h: 0.5, fontSize: 18, bold: true, color: C.text2, objectName: 'findings title' });
  const fixes = ['Index rebuild: from days to under an hour', 'Long jobs no longer run twice', 'Uploads stay fast with a million documents', 'Failed background steps are retried automatically', 'Overload answers "try again", never errors', 'Search outages of 20 minutes are ridden out'];
  text(s, fixes.map((t, j) => ({ text: t, options: { bullet: true, breakLine: j < fixes.length - 1 } })),
    { x: 7.9, y: 2.4, w: 4.6, h: 3.0, fontSize: 14, paraSpaceAfter: 8, objectName: 'findings list' });
  text(s, 'Recovery time measured: about 80 minutes for documents, 2 h 15 min including search, on one machine.',
    { x: 7.9, y: 5.5, w: 4.6, h: 0.9, fontSize: 13, italic: true, color: C.accent5, objectName: 'rto note' });
  s.addNotes('Rehearsals broke the real stack: SIGKILL of the worker, docker restart of OpenSearch, docker stop of the object store. Details in docs/perf/resilience.md and backup-restore.md. Production recovery point needs continuous database backup and replicated storage.');

  // 11. OCR chart
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Evidence' });
  s.addText('OCR: keep Tesseract, it reads Arabic bank documents best', { placeholder: 'title' });
  s.addChart(pres.charts.BAR, [
    { name: 'Tesseract (current engine)', labels: ['English', 'Arabic'], values: [97.5, 80.8] },
    { name: 'PaddleOCR', labels: ['English', 'Arabic'], values: [100, 17.5] },
  ], { x: 0.6, y: 1.45, w: 7.4, h: 5.2, barDir: 'col', barGrouping: 'clustered', chartColors: [HEX.blue, HEX.brass],
    showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0.0"%"', dataLabelFontSize: 13, dataLabelColor: HEX.graphite,
    dataLabelFontFace: '+mn-lt', catAxisLabelFontSize: 14, catAxisLabelColor: HEX.graphite, catAxisLabelFontFace: '+mn-lt',
    valAxisMaxVal: 110, valAxisMinVal: 0, valAxisMajorUnit: 20, valAxisLabelFormatCode: '0"%"', valAxisLabelFontSize: 11, valAxisLabelColor: HEX.slate,
    valAxisLabelFontFace: '+mn-lt', valGridLine: { color: 'E3E8EE', size: 0.5 }, catGridLine: { style: 'none' },
    showLegend: true, legendPos: 'b', legendFontSize: 13, legendFontFace: '+mn-lt', legendColor: HEX.graphite,
    showTitle: true, title: 'Fields found exactly (names, IDs, accounts, amounts, dates)', titleFontSize: 14, titleColor: HEX.lapis,
    titleFontFace: '+mn-lt', objectName: 'ocr chart' });
  const ocr = [['2 s', 'per page with Tesseract, against 10 to 12 s with PaddleOCR on the same machine'],
    ['5% / 36%', 'character errors in Arabic: Tesseract / PaddleOCR (it drops the Western digits in Arabic lines)'],
    ['0.2%', 'character errors in English with Tesseract']];
  ocr.forEach(([v, l], i) => {
    text(s, v, { x: 8.5, y: 1.6 + i * 1.6, w: 4.2, h: 0.6, fontSize: 30, bold: true, color: C.accent1, fontFace: 'Cambria', objectName: 'ocr stat ' + i });
    text(s, l, { x: 8.5, y: 2.2 + i * 1.6, w: 4.2, h: 0.8, fontSize: 14, objectName: 'ocr label ' + i });
  });
  s.addNotes('48 synthetic banking pages in English and Arabic at three scan qualities (the bank\'s real samples, decision O-02, are still needed). Full results: docs/perf/ocr-benchmark.md.');

  // 12. Migration
  pres.addSection({ title: 'Path forward' });
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Path forward' });
  s.addText('Moving from JDMS: controlled, repeatable, proven', { placeholder: 'title' });
  const stepsM = [
    ['1', 'Propose the catalog', 'Document types, fields, folders and folder permissions read from JDMS and proposed'],
    ['2', 'Administrators approve', 'Two bank administrators review and approve every proposal'],
    ['3', 'Import documents', 'Every version, approval history and permission; files re-encrypted with the new keys'],
    ['4', 'Reconcile', 'Report per run: counts, checksums of every file, permissions per user, exceptions with reasons'],
    ['5', 'Cut over', 'Re-runs are safe; a delta run picks up only what changed since the last one'],
  ];
  stepsM.forEach(([n, h, b], i) => {
    const x = 0.6 + i * 2.48;
    s.addShape(pres.shapes.OVAL, { x: x + 0.75, y: 1.6, w: 0.7, h: 0.7, fill: { color: i === 4 ? HEX.brass : HEX.blue }, line: { color: HEX.white }, objectName: 'step ' + n });
    text(s, n, { x: x + 0.75, y: 1.6, w: 0.7, h: 0.7, fontSize: 20, bold: true, color: HEX.white, align: 'center', valign: 'middle', objectName: 'step number ' + n });
    if (i < 4) s.addShape(pres.shapes.LINE, { x: x + 1.5, y: 1.95, w: 1.73, h: 0, line: { color: HEX.rule, width: 2 }, objectName: 'step link ' + n });
    text(s, h, { x, y: 2.55, w: 2.25, h: 0.7, fontSize: 16, bold: true, color: C.text2, align: 'center', objectName: 'step title ' + n });
    text(s, b, { x, y: 3.3, w: 2.25, h: 1.8, fontSize: 14, align: 'center', objectName: 'step text ' + n });
  });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.6, y: 5.35, w: 12.1, h: 1.25, fill: { color: HEX.mist }, line: { color: HEX.mist }, objectName: 'migration result' });
  text(s, [{ text: 'Rehearsed: ', options: { bold: true, color: HEX.lapis } }, { text: 'every file\'s checksum matched after re-encryption, permissions matched the legacy system user by user, and the legacy encryption key never leaves the migration tool.' }],
    { x: 0.9, y: 5.55, w: 11.5, h: 0.9, fontSize: 15, valign: 'middle', objectName: 'migration result text' });
  s.addNotes('Documents still in a legacy approval are finished in JDMS before cut-over. The legacy file layout and permission codes are confirmed against a real bank\'s samples (O-04).');

  // 13. Business advantages
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Path forward' });
  s.addText('What the bank gains', { placeholder: 'title' });
  const gains = [
    [I.shield, HEX.red, 'Lower risk', 'Legacy security gaps closed; every access controlled and audited'],
    [I.check, HEX.green, 'Regulatory confidence', 'Retention, legal holds, two-person controls and an audit trail examiners can verify'],
    [I.bolt, HEX.blue, 'Faster service', 'Documents found in under a second, including scanned content'],
    [I.coins, HEX.brass, 'No licence fees', 'Open-source components under permissive licences; no vendor lock-in'],
    [I.building, HEX.lapis, 'Ready to grow', 'Several banks on one platform, kept strictly apart; scales out with load'],
    [I.lang, HEX.slate, 'Built for the region', 'Arabic and English throughout; FLEXCUBE integration in mind'],
  ];
  gains.forEach(([img, col, h, b], i) => {
    const x = 0.6 + (i % 2) * 6.15; const y = 1.5 + Math.floor(i / 2) * 1.75;
    badge(s, img, x, y, 0.8, col, h);
    text(s, h, { x: x + 1.05, y: y + 0.02, w: 4.9, h: 0.45, fontSize: 18, bold: true, color: C.text2, objectName: h + ' title' });
    text(s, b, { x: x + 1.05, y: y + 0.48, w: 4.9, h: 0.9, fontSize: 14, objectName: h + ' text' });
  });
  s.addNotes('Commercial angle: JDMS Next is a product sold to banks. The same platform serves several banks with proven isolation, which lowers the cost per bank.');

  // 14. Screens
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Path forward' });
  s.addText('A modern interface in English and Arabic', { placeholder: 'title' });
  const pic = (file, x, y, w, name) => {
    const buf = fs.readFileSync(file); const iw = buf.readUInt32BE(16); const ih = buf.readUInt32BE(20);
    const h = w * Math.min(ih, 900) / iw;
    s.addImage({ path: file, x, y, w, h, sizing: { type: 'crop', w, h }, objectName: name,
      altText: name, shadow: { type: 'outer', blur: 6, offset: 2, angle: 90, color: '000000', opacity: 0.2 } });
    return h;
  };
  const h1 = pic(`${SHOTS}/06-document-overview.png`, 0.6, 1.5, 5.9, 'Document overview screenshot');
  pic(`${SHOTS}/26-ar-home.png`, 6.8, 1.5, 5.9, 'Arabic home screenshot');
  text(s, 'Document with watermarked preview, versions and approval history', { x: 0.6, y: 1.6 + h1, w: 5.9, h: 0.5, fontSize: 13, color: C.accent5, objectName: 'caption 1' });
  text(s, 'The same system in Arabic, right to left', { x: 6.8, y: 1.6 + h1, w: 5.9, h: 0.5, fontSize: 13, color: C.accent5, objectName: 'caption 2' });
  s.addNotes('All screens: Home, Browse, Search, Upload, Document, Tasks, Recycle bin, Records, Administration, Audit. See the User Manual for the full set.');

  // 15. Roadmap and decisions
  s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: 'Path forward' });
  s.addText('Decisions needed to move to a pilot bank', { placeholder: 'title' });
  const phases = [['Phase 0', 'Proof of concept', 'Done', HEX.green], ['Next', 'Decisions and hardening', 'Now', HEX.brass],
    ['Pilot', 'First bank, Oracle, real documents', 'After decisions', HEX.blue], ['Release', 'Production for banks', 'After pilot', HEX.lapis]];
  phases.forEach(([a, b, c, col], i) => {
    const x = 0.6 + i * 3.07;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y: 1.5, w: 2.85, h: 1.35, rectRadius: 0.08, fill: { color: col }, line: { color: col }, objectName: 'phase ' + a });
    text(s, [{ text: a, options: { bold: true, fontSize: 18, color: HEX.white, breakLine: true } }, { text: b, options: { fontSize: 13, color: HEX.white, breakLine: true } },
      { text: c, options: { fontSize: 12, italic: true, color: HEX.ice } }], { x: x + 0.15, y: 1.6, w: 2.55, h: 1.15, valign: 'middle', objectName: 'phase text ' + a });
  });
  const dec = (t) => ({ text: t, options: { bold: true, color: HEX.white, fill: { color: HEX.lapis }, fontSize: 14 } });
  s.addTable([
    [dec('Decision'), dec('Why it matters'), dec('Owner')],
    ['Production object store with WORM (O-01)', 'Immutable storage for records regulators require', 'IT infrastructure'],
    ['Bank sample documents (O-02)', 'Confirm OCR and extraction on real scans', 'Pilot bank'],
    ['FLEXCUBE interfaces and payloads (O-03)', 'Wire the core banking integration', 'Pilot bank, integration team'],
    ['Performance and recovery targets (O-06)', 'Size production hardware; set RPO and RTO', 'Product owner'],
    ['Oracle database run (B-14, deferred)', 'Most target banks run Oracle; needs licence approval', 'Product owner'],
  ], { x: 0.6, y: 3.15, w: 12.1, colW: [4.3, 5.0, 2.8], fontSize: 14, fontFace: 'Calibri', color: HEX.graphite,
    border: { type: 'solid', pt: 0.75, color: HEX.rule }, rowH: 0.55, valign: 'middle', margin: [0.04, 0.1, 0.04, 0.1], objectName: 'decisions table' });
  s.addNotes('Open items from docs/01-decisions.md. None blocks a pilot preparation start; each must be decided before go-live. Also to plan: a run on production-like hardware, and staffing for the pilot.');

  // 16. Closing
  s = pres.addSlide({ masterName: 'DARK', sectionTitle: 'Path forward' });
  s.addText('Recommendation: proceed to a pilot bank', { placeholder: 'title' });
  const next = ['Approve the move from proof of concept to pilot preparation', 'Take the five open decisions on the previous slide',
    'Choose the pilot bank and obtain its sample documents and FLEXCUBE details', 'Run the measurements again on production-like hardware'];
  badge(s, I.flag, 0.6, 1.8, 1.1, HEX.brass, 'next steps');
  text(s, 'Next steps', { x: 2.0, y: 1.9, w: 6, h: 0.6, fontSize: 24, bold: true, color: HEX.white, fontFace: 'Cambria', objectName: 'next steps title' });
  text(s, next.map((t, j) => ({ text: t, options: { bullet: { type: 'number' }, breakLine: j < next.length - 1 } })),
    { x: 2.0, y: 2.7, w: 10.3, h: 2.8, fontSize: 20, color: HEX.white, paraSpaceAfter: 14, objectName: 'next steps list' });
  text(s, 'Supporting documents: User Manual, Installation Guide, Testing Guide, and the performance evidence in docs/perf.',
    { x: 2.0, y: 6.0, w: 10.3, h: 0.5, fontSize: 14, color: HEX.ice, italic: true, objectName: 'supporting docs' });
  s.addNotes('Close on the decision requested. Offer a live demonstration with the test users described in the Testing Guide.');

  await pres.writeFile({ fileName: OUT });
  await applyTheme(OUT, THEME);
  console.log('wrote', OUT);
})();
