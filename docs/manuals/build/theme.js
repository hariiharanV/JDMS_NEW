// Writes the deck's colour scheme and theme name into ppt/theme/theme1.xml.
// pptxgenjs cannot set theme colours itself, so scheme colours would otherwise resolve to Office's palette.
const fs = require('fs');
const JSZip = require('jszip');

const SLOTS = ['dk1', 'lt1', 'dk2', 'lt2', 'accent1', 'accent2', 'accent3', 'accent4', 'accent5', 'accent6', 'hlink', 'folHlink'];
const THEME_PART = 'ppt/theme/theme1.xml';

async function applyTheme(deckPath, theme) {
  const zip = await JSZip.loadAsync(fs.readFileSync(deckPath));
  const name = theme.name.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
  const scheme = `<a:clrScheme name="${name}">`
    + SLOTS.map((k) => {
      if (!/^[0-9A-F]{6}$/i.test(theme.colors[k])) throw new Error(`theme colour ${k} must be six hex digits`);
      return `<a:${k}><a:srgbClr val="${theme.colors[k].toUpperCase()}"/></a:${k}>`;
    }).join('')
    + '</a:clrScheme>';
  const xml = (await zip.file(THEME_PART).async('string'))
    .replace(/<a:clrScheme\b[\s\S]*?<\/a:clrScheme>/, () => scheme)
    .replace(/(<a:(?:theme|fontScheme)\b[^>]*?\bname=")[^"]*"/g, (_, head) => `${head}${name}"`);
  if (!xml.includes(scheme)) throw new Error(`${THEME_PART} has no colour scheme to replace`);
  zip.file(THEME_PART, xml);
  fs.writeFileSync(deckPath, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }));
}

module.exports = { applyTheme };
