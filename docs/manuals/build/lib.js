// Shared helpers for the JDMS Next Word documents (docx-js)
const fs = require('fs');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, Table, TableRow, TableCell, WidthType,
  ShadingType, BorderStyle, ImageRun, PageBreak, Header, Footer, PageNumber, TableOfContents, LevelFormat,
  TabStopType, PageOrientation, Bookmark, InternalHyperlink, PositionalTab, PositionalTabAlignment,
  PositionalTabRelativeTo, PositionalTabLeader, Tab,
} = require('docx');

const BRAND = { lapis: '163A63', blue: '2357A6', brass: 'B08423', mist: 'EEF2F5', graphite: '1E2933', slate: '56657A',
  rule: 'D3DAE2', note: 'EAF1FA', warn: 'FBF3E2' };
const PAGE_W = 11906; // A4 width (DXA)
const MARGIN = 1134;  // 2 cm
const CONTENT_W = PAGE_W - 2 * MARGIN; // 9638

function runs(text, base = {}) {
  // **bold** and `code` inline markup
  const out = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0; let m;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push(new TextRun({ text: text.slice(last, m.index), ...base }));
    const t = m[0];
    if (t.startsWith('**')) out.push(new TextRun({ text: t.slice(2, -2), bold: true, ...base }));
    else out.push(new TextRun({ text: t.slice(1, -1), font: 'Consolas', size: 19, color: '7A2E0E', ...base }));
    last = m.index + t.length;
  }
  if (last < text.length) out.push(new TextRun({ text: text.slice(last), ...base }));
  return out;
}

const p = (text, opts = {}) => new Paragraph({ children: runs(text, opts.run || {}), spacing: { after: 120 },
  alignment: opts.align, ...(opts.para || {}) });
const headings = [];
function heading(level, text, extra = {}) {
  const id = `h${headings.length + 1}`;
  if (level <= 2) headings.push({ level, text, id });
  return new Paragraph({ heading: level === 1 ? HeadingLevel.HEADING_1 : HeadingLevel.HEADING_2,
    children: [new Bookmark({ id, children: [new TextRun(text)] })], ...extra });
}
const h1 = (text) => heading(1, text, { pageBreakBefore: true });
const h1nb = (text) => heading(1, text);
const h2 = (text) => heading(2, text);
const h3 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] });
const bullet = (text, level = 0) => new Paragraph({ numbering: { reference: 'bullets', level }, children: runs(text),
  spacing: { after: 60 } });
const bullets = (items) => items.map((i) => (Array.isArray(i) ? bullet(i[0], 1) : bullet(i)));
let numberingSeq = 0;
function steps(items) {
  // a fresh numbered list (restart at 1) each call
  numberingSeq += 1;
  const ref = `steps${numberingSeq}`;
  stepRefs.push(ref);
  return items.map((i) => new Paragraph({ numbering: { reference: ref, level: 0 }, children: runs(i), spacing: { after: 60 } }));
}
const stepRefs = [];

function code(lines) {
  const arr = Array.isArray(lines) ? lines : lines.split('\n');
  return arr.map((line, idx) => new Paragraph({
    children: [new TextRun({ text: line || ' ', font: 'Consolas', size: 18, color: '1E2933' })],
    shading: { fill: 'F3F5F7', type: ShadingType.CLEAR, color: 'auto' },
    spacing: { after: idx === arr.length - 1 ? 160 : 0, before: idx === 0 ? 60 : 0 },
    indent: { left: 170, right: 170 },
  }));
}

function note(text, kind = 'note') {
  const fill = kind === 'warn' ? BRAND.warn : BRAND.note;
  const label = kind === 'warn' ? 'Important: ' : 'Note: ';
  return new Paragraph({
    children: [new TextRun({ text: label, bold: true, color: kind === 'warn' ? '8A5A00' : BRAND.lapis }), ...runs(text)],
    shading: { fill, type: ShadingType.CLEAR, color: 'auto' },
    border: { left: { style: BorderStyle.SINGLE, size: 18, color: kind === 'warn' ? BRAND.brass : BRAND.blue, space: 8 } },
    spacing: { before: 120, after: 160 }, indent: { left: 200, right: 200 },
  });
}

const border = { style: BorderStyle.SINGLE, size: 4, color: BRAND.rule };
const borders = { top: border, bottom: border, left: border, right: border };

function table(header, rows, widths, opts = {}) {
  const size = opts.size || 19;
  const total = widths.reduce((a, b) => a + b, 0);
  const scale = CONTENT_W / total;
  const w = widths.map((x) => Math.floor(x * scale));
  w[w.length - 1] += CONTENT_W - w.reduce((a, b) => a + b, 0);
  const cell = (text, i, head) => new TableCell({
    borders, width: { size: w[i], type: WidthType.DXA },
    shading: head ? { fill: BRAND.lapis, type: ShadingType.CLEAR, color: 'auto' } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: String(text).split('\n').map((line) => new Paragraph({
      children: runs(line, head ? { bold: true, color: 'FFFFFF', size } : { size }), spacing: { after: 20 } })),
  });
  return new Table({
    width: { size: CONTENT_W, type: WidthType.DXA }, columnWidths: w,
    rows: [new TableRow({ tableHeader: true, children: header.map((t, i) => cell(t, i, true)) }),
      ...rows.map((r, ri) => new TableRow({ children: r.map((t, i) => {
        const c = cell(t, i, false);
        return c;
      }) }))],
  });
}

let figureNo = 0;
function figure(path, caption, maxWidthPx = 640) {
  if (!fs.existsSync(path)) {
    return [p(`[Screenshot not available: ${caption}]`, { run: { italics: true, color: 'A00000' } })];
  }
  const buf = fs.readFileSync(path);
  // PNG size from IHDR
  const width = buf.readUInt32BE(16); const height = buf.readUInt32BE(20);
  const w = Math.min(maxWidthPx, width);
  const h = Math.round(height * (w / width));
  figureNo += 1;
  return [
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 60 },
      children: [new ImageRun({ type: 'png', data: buf, transformation: { width: w, height: h },
        altText: { title: caption, description: caption, name: `figure${figureNo}` } })],
      border: { top: border, bottom: border, left: border, right: border } }),
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 },
      children: [new TextRun({ text: `Figure ${figureNo}. ${caption}`, italics: true, size: 18, color: BRAND.slate })] }),
  ];
}

function cover({ title, subtitle, audience, version = '1.0', date = '25 October 2026' }) {
  return [
    new Paragraph({ spacing: { before: 1800, after: 0 }, children: [
      new TextRun({ text: 'JDMS', bold: true, color: BRAND.brass, size: 56, font: 'Calibri' }),
      new TextRun({ text: ' Next', color: BRAND.lapis, size: 56, font: 'Calibri' })] }),
    new Paragraph({ spacing: { after: 600 }, children: [new TextRun({ text: 'Bank-grade document management',
      color: BRAND.slate, size: 24 })],
      border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: BRAND.brass, space: 12 } } }),
    new Paragraph({ spacing: { after: 200 }, children: [new TextRun({ text: title, bold: true, size: 60,
      color: BRAND.lapis })] }),
    new Paragraph({ spacing: { after: 1200 }, children: [new TextRun({ text: subtitle, size: 28, color: BRAND.graphite })] }),
    table(['Document', 'Value'], [
      ['Audience', audience], ['Version', version], ['Date', date],
      ['Product release', 'JDMS Next 0.1.0, Phase 0 (proof of concept), backlog items B-00 to B-15'],
      ['Classification', 'Internal'],
    ], [2, 6]),
    new Paragraph({ children: [new PageBreak()] }),
  ];
}

const TOC_MARK = { tocPlaceholder: true };
function toc() {
  return [new Paragraph({ children: [new TextRun({ text: 'Contents', bold: true, size: 32, color: BRAND.lapis })],
    spacing: { after: 200 } }), TOC_MARK];
}

// Static contents: hyperlinked entries; page numbers from a first render (TOC_PAGES json: heading text -> page)
function tocEntries() {
  let pages = {};
  if (process.env.TOC_PAGES && fs.existsSync(process.env.TOC_PAGES)) pages = JSON.parse(fs.readFileSync(process.env.TOC_PAGES));
  return headings.map((h) => new Paragraph({
    spacing: { after: h.level === 1 ? 60 : 30, before: h.level === 1 ? 100 : 0 },
    indent: { left: h.level === 1 ? 0 : 360 },
    tabStops: [{ type: TabStopType.RIGHT, position: CONTENT_W - 10, leader: 'dot' }],
    children: [new InternalHyperlink({ anchor: h.id, children: [
      new TextRun({ text: h.text, bold: h.level === 1, size: h.level === 1 ? 21 : 20 }),
      new TextRun({ children: [new Tab(), String(pages[h.text] ?? '00')], size: 20, bold: h.level === 1 })] })],
  }));
}

function build(file, docTitle, children) {
  const numberingConfig = [
    { reference: 'bullets', levels: [
      { level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 540, hanging: 270 } } } },
      { level: 1, format: LevelFormat.BULLET, text: '–', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 1080, hanging: 270 } } } }] },
    ...stepRefs.map((ref) => ({ reference: ref, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.',
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 360 } } } }] })),
  ];
  const doc = new Document({
    creator: 'JDMS Next project', title: docTitle, description: docTitle,
    features: { updateFields: true },
    styles: {
      default: { document: { run: { font: 'Calibri', size: 21, color: BRAND.graphite } } },
      paragraphStyles: [
        { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 34, bold: true, color: BRAND.lapis, font: 'Calibri' },
          paragraph: { spacing: { before: 240, after: 200 }, outlineLevel: 0 } },
        { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 27, bold: true, color: BRAND.blue, font: 'Calibri' },
          paragraph: { spacing: { before: 280, after: 120 }, outlineLevel: 1, keepNext: true } },
        { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 23, bold: true, color: BRAND.graphite, font: 'Calibri' },
          paragraph: { spacing: { before: 200, after: 100 }, outlineLevel: 2, keepNext: true } },
      ],
    },
    numbering: { config: numberingConfig },
    sections: [{
      properties: { page: { size: { width: PAGE_W, height: 16838 },
        margin: { top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN } }, titlePage: true },
      headers: { default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT,
        children: [new TextRun({ text: `JDMS Next  |  ${docTitle}`, size: 16, color: BRAND.slate })] })] }) },
      footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER,
        children: [new TextRun({ text: 'Page ', size: 16, color: BRAND.slate }),
          new TextRun({ children: [PageNumber.CURRENT], size: 16, color: BRAND.slate }),
          new TextRun({ text: ' of ', size: 16, color: BRAND.slate }),
          new TextRun({ children: [PageNumber.TOTAL_PAGES], size: 16, color: BRAND.slate })] })] }),
        first: new Footer({ children: [new Paragraph({ children: [] })] }) },
      children: children.flat(Infinity).flatMap((x) => (x === TOC_MARK ? tocEntries() : [x])),
    }],
  });
  return Packer.toBuffer(doc).then((buf) => { fs.writeFileSync(file, buf); console.log('wrote', file); });
}

module.exports = { headings, p, h1, h1nb, h2, h3, bullet, bullets, steps, code, note, table, figure, cover, toc, build, BRAND,
  PageBreak, Paragraph, TextRun };
