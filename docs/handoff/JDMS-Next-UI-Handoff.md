# JDMS Next: UI redesign handoff spec

**For:** Claude Code (implementation) and the JDMS Next frontend team
**Design canvas:** https://claude.ai/artifact/9XbDGVeFj7JSc9SfnXyQvz (private until shared from its Share menu)
**Revision:** 2, 26 October 2026, aligned with the frontend team's technical brief
**Source of truth for current behaviour:** `manuals/JDMS-Next-User-Manual.docx`, `JDMS-Next-Testing-Guide.docx`

### Target stack (from the technical brief)

| Layer | Library |
|---|---|
| UI | React 19.3.1, TypeScript 6.0, Vite 8.3 |
| Components | **Ant Design 6.6.7**, @ant-design/icons 6.3.4 |
| Routing | React Router 8.5, lazy-loaded screens |
| Server data | TanStack Query 5 |
| i18n | i18next 26 / react-i18next 17, English and Arabic, full RTL |
| Sign-in | oidc-client-ts against Keycloak (OIDC + PKCE) |
| Viewer | pdfjs-dist 6.4, rendered to canvas |
| Fonts | IBM Plex Sans + IBM Plex Sans Arabic from @fontsource, weights **400/500/600 only** |
| Tests | Vitest, Testing Library, Playwright |

**Styling model:** antd global token plus component tokens in `src/theme.ts` → `ConfigProvider`. Plain CSS with custom properties and classes in `src/index.css`. Logical CSS properties only. No Tailwind, and no CSS-in-JS beyond antd's own.

---

## 0. How to use this document (read first, Claude Code)

1. Read the whole spec first. Sections 2–5 are shared foundations. Section 6 covers each screen.
2. Each screen has a **static HTML reference** in `designs/html/<Screen>.html` and a **screenshot** in `designs/png/<Screen>.png`. Every style is inline in the HTML, so exact values can be read from it. The PNG is the visual target.
3. The reference HTML is **not production code**. Rebuild it with antd components, the existing routes, TanStack Query hooks, i18next keys and auth. Don't paste the HTML in. The reference icons are hand-drawn SVGs. **Use the @ant-design/icons mapping in section 3.5.**
4. **Stay within Ant Design.** Restyle through `src/theme.ts` tokens and `src/index.css`. Don't add a component or icon library; that would need an ADR.
5. **Don't change backend behaviour or APIs.** Where a design needs data the API doesn't return, render the fallback in section 9 and add `// TODO(backend-gap: Gx)`.
6. Every string goes through i18next in `en` and `ar`. Leave room for text expansion: no fixed widths on buttons, labels or chips.
7. **Interruption rule (from the brief):** forms that don't need to interrupt the user **open in place** (inline panels, expanding sections, their own routes). An antd `Modal` is used **only for irreversible decisions that need a comment**. Section 4.13 lists exactly which actions qualify. Don't use Drawers for forms.
8. Work in the phases in section 8, one PR per phase. Run `vitest` and the Playwright suite after each phase, and update the tests listed in section 7.

### File map

| File | What it is |
|---|---|
| `designs/html/Main.html` · `png/Main.png` | Review board: the 9 findings, the chip before/after, and **the full status and classification scale** |
| `Home` · `HomeAr` | Home (maker), English and the Arabic RTL reference |
| `Tasks` | Tasks inbox, split view (checker) |
| `Document` | Document page |
| `Upload` | Upload |
| `Search` · `Browse` | Find screens |
| `Records` | Retention, legal holds, disposition (records officer) |
| `AdminTypes` · `AccessRules` | Administration (tenant admin; security admin) |
| `Audit` | Audit trail (security admin) |
| `SignIn` | Keycloak login theme |
| `png/*-mobile.png` | 390 px renders of Home, HomeAr and Tasks |

Text in square brackets in the designs, such as `[Bank display name]` or `[User name]`, marks data the screenshots didn't show. Bind it to real data, or follow section 9.

---

## 1. Goals and principles

The redesign keeps every function and permission rule. It changes layout, hierarchy, wording and visual language.

1. **Navigation scales with roles.** A grouped side menu (antd `Layout.Sider` + `Menu`) replaces the single row of top tabs.
2. **Decisions happen where the evidence is.** Checkers see the PDF preview and key fields next to Approve, Return and Reject.
3. **One primary action per screen.** Destructive actions are never primary. The confirmation depth follows section 4.13.
4. **People read names, not codes.** Codes and UUIDs appear only as small monospace secondary text.
5. **Status and classification are readable without colour.** The text is always shown, plus a shape (status dot) or weight/icon (classification).
6. **RTL is first-class.** Logical properties everywhere. The product wordmark stays LTR.
7. **Phone width works.** At 390 px the menu collapses, toolbars wrap, wide tables scroll inside their card, and the page never scrolls sideways.

---

## 2. Design tokens

### 2.1 Colour

The new palette replaces the "bank registry" palette. Section 2.5 maps old names to new ones so you can migrate `index.css` variables in place.

| CSS variable | Hex | Use |
|---|---|---|
| `--jdms-navy` | `#13294B` | Header, page titles, selected text, count badges, Restricted label |
| `--jdms-navy-700` | `#22395F` | Language Segmented track on the header |
| `--jdms-navy-line` | `#3A5078` | Dividers on navy |
| `--jdms-on-navy-muted` | `#C6D0DF` | Secondary text on navy |
| `--jdms-gold` | `#E0B456` | Wordmark "JDMS", avatar, environment tag |
| `--jdms-gold-accent` | `#C79A3B` | Active tab ink bar, keyboard focus ring |
| `--jdms-action` | `#1D4E89` | Primary buttons, links, checkbox and radio accents |
| `--jdms-ink` | `#1B2230` | Body text |
| `--jdms-ink-2` | `#4A5363` | Secondary text |
| `--jdms-ink-3` | `#5B6472` | Captions, menu group titles |
| `--jdms-ground` | `#F6F7F9` | Page background |
| `--jdms-surface` | `#FFFFFF` | Cards, sider, tables |
| `--jdms-sunken` | `#EEF0F3` | Viewer well, thumbnails, neutral chip, Confidential label |
| `--jdms-footer` | `#FAFBFC` | Sticky decision bar |
| `--jdms-selected` | `#E8EEF7` | Selected row, selected menu item, pressed toggle |
| `--jdms-border` | `#DCE0E6` | Card borders |
| `--jdms-border-strong` | `#C9D2DE` | Inputs, default buttons |
| `--jdms-divider` | `#E8EBEF` | Card header dividers |
| `--jdms-row` | `#F0F2F5` | Table row lines |
| `--jdms-success-fg` / `-bg` / `-border` | `#1F6B3A` / `#E6F4EA` / `#B9D3BF` | Active, Approved, Chain intact |
| `--jdms-warning-fg` / `-dot` / `-bg` / `-border` | `#7A4F00` / `#B07400` / `#FDF1D8` / `#E3C27A` | Pending approval, Due soon, Holding |
| `--jdms-neutral-fg` / `-bg` | `#3F4754` / `#EEF0F3` | Draft, Disposed |
| `--jdms-danger-fg` / `-bg` / `-border` | `#9A2626` / `#FBE7E7` / `#E6B7B7` | Rejected, Deleted, Denied, Reject button |
| `--jdms-mark` | `#FBE6B5` | Search hit highlight |

Every text/background pair used together is at least 4.5:1, and white on navy is 13:1. Don't reuse the old lighter status inks on tinted backgrounds.

### 2.2 Typography, spacing, shape, motion

- **Fonts:** the existing @fontsource IBM Plex Sans / IBM Plex Sans Arabic at **400/500/600**. **The maximum weight is 600**: the designs use 600 for titles, numbers and the wordmark, so no 700 asset is needed. Codes and IDs use the **system monospace stack** `ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace` (no new font asset). In Arabic: `'IBM Plex Sans Arabic', 'IBM Plex Sans', system-ui, sans-serif`.
- **Type scale** (from the current 22/18/16):

| Role | Size / weight |
|---|---|
| Page title (H1) | 28 / 600, navy |
| Detail title | 20–22 / 600 |
| Section title | 16–18 / 600 |
| Body | 14 / 400, line-height 1.5 |
| Secondary | 13 / 400, ink-2 |
| Label, caption | 12 / 500 |
| Menu group title | 12 / 600, uppercase, letter-spacing 0.06em (Arabic: no uppercase or letter-spacing) |
| Stat number | 30 / 600, navy |
| Code, ID | mono 11–12, ink-3 |

- **Spacing (px):** 4, 6, 8, 10, 12, 14, 16, 20, 24, 32, 48. Card padding 20–24. Content padding 24–32 (16 at phone width).
- **Radius:** chips 999; tags and classification labels 4; inputs and buttons 8 (the current 6 becomes 8); cards 10.
- **Control height:** **40** (up from 34); primary page actions 44 (antd `size="large"`). This keeps touch targets above 40.
- **Shadows:** none, except the viewer page in its well (`0 1px 3px rgba(20,30,50,.12)`). This keeps the current flat look.
- **Focus:** `:focus-visible { outline: 2px solid var(--jdms-gold-accent); outline-offset: 1px; }` on everything, including antd components (turn off antd's own focus glow; section 2.4).
- **Motion:** antd motion stays on by default. Under `prefers-reduced-motion: reduce`, set the antd token `motion: false` and turn off CSS transitions and animations (section 2.3).

### 2.3 `src/index.css` additions

```css
:root {
  --jdms-navy:#13294B; --jdms-navy-700:#22395F; --jdms-navy-line:#3A5078; --jdms-on-navy-muted:#C6D0DF;
  --jdms-gold:#E0B456; --jdms-gold-accent:#C79A3B; --jdms-action:#1D4E89;
  --jdms-ink:#1B2230; --jdms-ink-2:#4A5363; --jdms-ink-3:#5B6472;
  --jdms-ground:#F6F7F9; --jdms-surface:#FFFFFF; --jdms-sunken:#EEF0F3; --jdms-footer:#FAFBFC;
  --jdms-selected:#E8EEF7; --jdms-border:#DCE0E6; --jdms-border-strong:#C9D2DE; --jdms-divider:#E8EBEF; --jdms-row:#F0F2F5;
  --jdms-success-fg:#1F6B3A; --jdms-success-bg:#E6F4EA; --jdms-success-border:#B9D3BF;
  --jdms-warning-fg:#7A4F00; --jdms-warning-dot:#B07400; --jdms-warning-bg:#FDF1D8; --jdms-warning-border:#E3C27A;
  --jdms-neutral-fg:#3F4754; --jdms-neutral-bg:#EEF0F3;
  --jdms-danger-fg:#9A2626; --jdms-danger-bg:#FBE7E7; --jdms-danger-border:#E6B7B7;
  --jdms-mark:#FBE6B5;
  --jdms-font-mono: ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace;
}
html[lang="ar"] body { font-family: 'IBM Plex Sans Arabic', 'IBM Plex Sans', system-ui, sans-serif; }
:focus-visible { outline: 2px solid var(--jdms-gold-accent); outline-offset: 1px; }

.jdms-skip-link { position: absolute; inset-inline-start: 16px; inset-block-start: -48px; z-index: 1000;
  background: var(--jdms-surface); color: var(--jdms-navy); padding: 10px 14px; border-radius: 8px; font-weight: 600; }
.jdms-skip-link:focus { inset-block-start: 12px; }

.jdms-wordmark { direction: ltr; unicode-bidi: isolate; font-weight: 600; font-size: 20px; color: #fff; }
.jdms-wordmark b { color: var(--jdms-gold); font-weight: 600; }
.jdms-code { font-family: var(--jdms-font-mono); font-size: 12px; color: var(--jdms-ink-3); }
[dir="rtl"] .jdms-flip-rtl { transform: scaleX(-1); }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: 0.01ms !important; animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important; scroll-behavior: auto !important; }
}
```

### 2.4 `src/theme.ts` (antd 6)

These are antd v5/v6 token names. Run `tsc` after the change and fix any key the antd 6.6.7 typings reject.

```ts
import type { ThemeConfig } from 'antd';

const reducedMotion =
  typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export const buildTheme = (lang: 'en' | 'ar'): ThemeConfig => ({
  token: {
    colorPrimary: '#1D4E89',
    colorLink: '#1D4E89',
    colorText: '#1B2230',
    colorTextSecondary: '#4A5363',
    colorTextTertiary: '#5B6472',
    colorBorder: '#C9D2DE',
    colorBorderSecondary: '#DCE0E6',
    colorSplit: '#F0F2F5',
    colorBgLayout: '#F6F7F9',
    colorBgContainer: '#FFFFFF',
    colorFillAlter: '#FAFBFC',
    colorSuccess: '#1F6B3A',
    colorWarning: '#B07400',
    colorError: '#9A2626',
    colorInfo: '#1D4E89',
    fontFamily: lang === 'ar'
      ? "'IBM Plex Sans Arabic', 'IBM Plex Sans', system-ui, sans-serif"
      : "'IBM Plex Sans', system-ui, sans-serif",
    fontFamilyCode: "ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace",
    fontSize: 14,
    fontSizeHeading1: 28, fontSizeHeading2: 22, fontSizeHeading3: 18, fontSizeHeading4: 16,
    fontWeightStrong: 600,
    borderRadius: 8, borderRadiusSM: 4, borderRadiusLG: 10,
    controlHeight: 40, controlHeightLG: 44, controlHeightSM: 32,
    boxShadow: 'none', boxShadowSecondary: '0 4px 16px rgba(20,30,50,.12)', // menus and popovers only
    controlOutline: 'transparent',  // focus is the gold :focus-visible ring
    lineWidthFocus: 0,
    motion: !reducedMotion,
  },
  components: {
    Layout: { headerBg: '#13294B', headerHeight: 64, headerPadding: '0 24px', siderBg: '#FFFFFF', bodyBg: '#F6F7F9' },
    Menu: {
      itemBg: '#FFFFFF', itemColor: '#1B2230', itemSelectedBg: '#E8EEF7', itemSelectedColor: '#13294B',
      itemHoverBg: '#F0F2F5', itemBorderRadius: 8, itemHeight: 40, groupTitleColor: '#5B6472', groupTitleFontSize: 12,
      activeBarBorderWidth: 0,
    },
    Card: { headerFontSize: 16, paddingLG: 20, colorBorderSecondary: '#DCE0E6' },
    Table: {
      headerBg: '#FFFFFF', headerColor: '#4A5363', headerSplitColor: 'transparent',
      rowSelectedBg: '#E8EEF7', rowSelectedHoverBg: '#E0E8F3', rowHoverBg: '#F6F7F9', borderColor: '#F0F2F5',
      cellPaddingBlock: 12,
    },
    Tabs: { inkBarColor: '#C79A3B', itemSelectedColor: '#13294B', itemColor: '#4A5363', itemHoverColor: '#13294B' },
    Button: { fontWeight: 600, primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none', defaultBorderColor: '#C9D2DE' },
    Segmented: { trackBg: '#E8EBEF', itemSelectedBg: '#FFFFFF', itemSelectedColor: '#13294B', itemColor: '#1B2230' },
    Tag: { defaultBg: '#EEF0F3', defaultColor: '#3F4754' },
  },
});

// App root
<ConfigProvider theme={buildTheme(lang)} direction={lang === 'ar' ? 'rtl' : 'ltr'} locale={lang === 'ar' ? arEG : enUS}>
```

On the navy header, the language `Segmented` uses an override class: track `--jdms-navy-700`, item colour white, selected item white background with navy text (see the `Home.html` header).

### 2.5 Old → new token mapping

| Current name | Current | New variable | New |
|---|---|---|---|
| Lapis / deep | `#163A63` / `#0F2C4D` | `--jdms-navy` | `#13294B` (one navy; the second header band is removed) |
| Registry blue | `#2357A6` | `--jdms-action` | `#1D4E89` |
| Brass | `#B08423` | `--jdms-gold` (wordmark) / `--jdms-gold-accent` (ink bar, focus) | `#E0B456` / `#C79A3B` |
| Mist | `#EEF2F5` | `--jdms-ground` | `#F6F7F9` |
| Graphite / Slate | `#1E2933` / `#56657A` | `--jdms-ink` / `--jdms-ink-2` | `#1B2230` / `#4A5363` |
| Rule | `#D3DAE2` | `--jdms-border` | `#DCE0E6` |
| approved / pending / rejected / deleted inks | `#2F7D4F` / `#9A6200` / `#B3261E` / `#8A3B12` | success / warning / danger fg on tinted bg | section 4.3 |

Brass keeps its meaning of "you are here": the active tab ink bar, the focus ring and the wordmark. The selected menu item uses `--jdms-selected` with navy text.

---

## 3. Layout, RTL, wording, icons

### 3.1 App shell (all signed-in screens): `Home.html`, `HomeAr.html`

- **Skip link:** the first focusable element is `<a class="jdms-skip-link" href="#main">Skip to main content</a>` (ar: "تخطَّ إلى المحتوى الرئيسي"). `<main id="main" tabIndex={-1}>`.
- **Header** (`Layout.Header`, navy, flex-wrap):
  - Wordmark `<span class="jdms-wordmark"><b>JDMS</b> Next</span>`, always LTR.
  - Divider and the bank **display name** (G1).
  - **Global search:** antd `Input` with a `SearchOutlined` prefix, max width 560, grows to fill. Placeholder "Search title, CIF or document number". Enter goes to `/search?q=`. The `/` key focuses it, unless focus is already in a field.
  - On the inline-end side: language `Segmented` (EN / عربي); notifications `Button type="text" icon={<BellOutlined/>}` with `aria-label`, backed by `GET /api/v1/me/notifications`, and a `Badge` when there are unread items; user `Dropdown` button (avatar initials + name + "Role · Branch") with Profile and Sign out.
- **Side menu** (`Layout.Sider` width 220, `theme="light"`, border on the inline-end side) holding `Menu mode="inline"` with `type: 'group'` items. Show only what the user's roles allow; hide the rest (never disabled). Tasks has a count `Badge`. Role table (from the Testing Guide):

| Group | Item | Roles |
|---|---|---|
| Work | Home | all |
| Work | Tasks | all |
| Work | Upload | maker |
| Find | Browse, Search | all |
| Governance | Records, Recycle bin | records officer |
| Admin | Document types | tenant admin |
| Admin / Security | Pending changes | tenant admin, security admin |
| Security | Access rules, Audit | security admin |

- **Content** (`Layout.Content`): padding 24–32. Dashboard pages (Home, Records) cap at `max-width: 1240px`; table pages are full width.
- **Responsive:** use the `Sider` with `breakpoint="lg"` and `collapsedWidth={0}`. Below `lg`, a header "Menu" button (`MenuOutlined`) toggles it. On a phone, the open menu sits over the content as navigation, which is allowed: the in-place rule covers forms, not navigation. Card rows use `Row`/`Col` with responsive spans, or CSS grid `repeat(auto-fit, minmax(…, 1fr))`, as in the references.

### 3.2 RTL rules

1. `<html lang dir>` follows the language; `ConfigProvider direction` handles antd.
2. **Logical properties only** (already the house rule).
3. Mirror directional icons with the `.jdms-flip-rtl` class: `SearchOutlined`, `RightOutlined`/`LeftOutlined` when you place them yourself (antd mirrors its own pagination and arrows), `ExportOutlined`, `ArrowRightOutlined`. Don't mirror check, lock, bell, plus, download, close or the wordmark.
4. Stored data (titles, CIF, references, IDs, file names) is wrapped in `<bdi>`, with `dir="ltr"` for codes and IDs. See `HomeAr.html`.
5. Digits: Western in both languages (a setting; decision D2). Use `Intl.NumberFormat(locale, { numberingSystem: 'latn' })`.
6. Arabic menu group titles: no uppercase or letter-spacing.

### 3.3 Shared wording (English → Arabic; the bank's Arabic reviewer confirms these, D3)

| Key | English | Arabic |
|---|---|---|
| a11y.skipToMain | Skip to main content | تخطَّ إلى المحتوى الرئيسي |
| nav.group.work / find / governance | Work / Find / Governance | العمل / البحث / الحوكمة |
| nav.home / tasks / upload | Home / Tasks / Upload | الرئيسية / المهام / رفع مستند |
| nav.browse / search / recycleBin | Browse / Search / Recycle bin | تصفح / بحث / سلة المحذوفات |
| search.placeholder | Search title, CIF or document number | ابحث بالعنوان أو رقم العميل أو رقم المستند |
| status.DRAFT / PENDING_APPROVAL / ACTIVE | Draft / Pending approval / Active | مسودة / بانتظار الموافقة / نشط |
| status.REJECTED / DELETED / DISPOSED | Rejected / Deleted / Disposed | مرفوض / محذوف / تم إتلافه |
| classification.PUBLIC / INTERNAL | Public / Internal | عام / داخلي |
| classification.CONFIDENTIAL / RESTRICTED | Confidential / Restricted | سري / مقيد |
| role.maker / checker | Maker / Checker | مُنشئ / مُدقق |
| home.greeting | Good morning, {{name}} | صباح الخير، {{name}} |
| home.needsAction / recent | Needs your action / Recent documents | بحاجة إلى إجراء منك / أحدث المستندات |
| table.title / cif / status / updated | Title / CIF / Status / Updated | العنوان / رقم العميل / الحالة / آخر تحديث |

### 3.4 Formatting

- **Dates:** one formatter. Full: `25 Oct 2026, 22:31`. In lists, the current year is dropped (`25 Oct, 22:31`). Date-only: `31 May 2031`. No ISO strings in the UI. Use `Intl.DateTimeFormat(locale, { day:'numeric', month:'short', year:'numeric', hour:'2-digit', minute:'2-digit', hour12:false })`.
- **Codes → names** for document type, cabinet, classification, approval process, role, action and resource type. A code may appear as a `.jdms-code` caption. Branch shows `name (code)` once names exist (G2).
- **Task titles:** a kind line ("Upload · Passport") plus the document title. No trailing `(PASSPORT)`.
- **IDs:** 8 characters + `…` in tables. Full value (mono, `word-break: break-all`) in details, with antd `Typography.Text copyable`.

### 3.5 Icons (@ant-design/icons, MIT)

| Use | Icon |
|---|---|
| Home / Tasks / Upload | `HomeOutlined` / `CheckSquareOutlined` / `UploadOutlined` |
| Browse / Search | `FolderOutlined` (`FolderOpenOutlined` for an expanded tree node) / `SearchOutlined` |
| Records / Recycle bin | `ContainerOutlined` / `DeleteOutlined` |
| Document types / Pending changes | `FileTextOutlined` / `ClockCircleOutlined` |
| Access rules / Audit | `SafetyOutlined` / `AuditOutlined` |
| Notifications / user menu / phone menu | `BellOutlined` / `DownOutlined` / `MenuOutlined` |
| Classification Public / Internal / Confidential / Restricted | `UnlockOutlined` / `LockOutlined` / `LockOutlined` / `LockFilled` |
| Download / New version / More | `DownloadOutlined` / `CloudUploadOutlined` / `MoreOutlined` |
| Check out / Check in | `EditOutlined` / `SaveOutlined` |
| Restore | `RollbackOutlined` |
| Open in full | `ExportOutlined` (flip in RTL) |
| Zoom | `ZoomOutOutlined` / `ZoomInOutlined` |
| Legal hold | `PauseCircleOutlined` |
| Retention / disposition | `FieldTimeOutlined` / `ScissorOutlined` |
| Chain intact / broken | `SafetyCertificateOutlined` / `WarningOutlined` |
| Filter / clear | `FilterOutlined` / `CloseOutlined` |
| Show / hide password (Keycloak theme) | inline SVG in the theme, or the Keycloak default |
| Allowed / Denied cell | `CheckOutlined` / `CloseOutlined` on a filled square |
| File | `FilePdfOutlined` / `FileOutlined` |

---

## 4. Shared components

Build these in phase 1. They're thin wrappers over antd; put them in `src/components/`, or wherever the repo's convention says.

### 4.1 `AppShell`
Section 3.1. One pure function, `buildNav(roles, counts): MenuProps['items']`, holds the role table so it can be unit-tested.

### 4.2 `PageHeader`
Optional breadcrumb (antd `Breadcrumb`) above an H1 (`Typography.Title level={1}`, 28/600 navy), an optional one-line description (14 ink-2), and an actions slot on the inline-end side (`Space wrap`). One primary button at most.

### 4.3 `StatusChip`
A pill: radius 999, padding 3 × 8, 12/600, a 6 px marker, gap 6. Build it with a `span` and CSS classes, **not** `Tag color="…"` (antd preset colours fail contrast).

| Status | fg / bg | Marker |
|---|---|---|
| DRAFT | neutral-fg / neutral-bg | hollow circle (1.5 px border) |
| PENDING_APPROVAL | warning-fg / warning-bg | filled circle, warning-dot |
| ACTIVE | success-fg / success-bg | filled circle |
| REJECTED | danger-fg / danger-bg | filled square (radius 1) |
| DELETED | danger-fg / danger-bg | hollow square |
| DISPOSED | neutral-fg / neutral-bg | horizontal bar 8 × 2 |

The same component also renders task and audit states: Due soon (warning), On time (success, text only), Overdue (danger), Holding (warning), Success / Denied / Error (success / danger / danger). See `Main.html` → "Full scale".

```ts
type DocStatus = 'DRAFT'|'PENDING_APPROVAL'|'ACTIVE'|'REJECTED'|'DELETED'|'DISPOSED';
```

### 4.4 `ClassificationLabel`
Heavier as the level rises, and never colour alone:

| Level | Look |
|---|---|
| PUBLIC | `UnlockOutlined` + text, 13/400 ink-2, no background |
| INTERNAL | `LockOutlined` + text, 13/500 ink-2, no background |
| CONFIDENTIAL | `LockOutlined` + text, 13/600 ink, sunken background, radius 4, padding 2–3 × 8–10 |
| RESTRICTED | `LockFilled` + text, 13/600 **white on navy**, radius 4 |

Always show it in detail views (Document, task detail, Browse properties). In lists and search results, show it only when the document is **above its type's minimum** (G3); this removes the "Confidential on every row" noise.

### 4.5 `StatCard`
An antd `Card` as a link (`hoverable`, `<a>` wrapper), holding a label (13 ink-2), a number (30/600 navy) and a hint (12). `tone="warning"` → warning border and a warning-fg hint at 600. It navigates to the filtered list.

### 4.6 Tables
antd `Table` styled by the component tokens (section 2.4):
- The first column is a title link (500–600) with a secondary line (type, 12 ink-3) underneath, replacing a separate type column.
- `className="tabular"` → `font-variant-numeric: tabular-nums` for IDs and numbers. Time columns align to the end.
- `scroll={{ x: 'max-content' }}` so wide tables scroll inside their card.
- Sorting uses antd's `sorter` (it gives `aria-sort`). Pagination `showTotal` reads "1–8 of 93".
- Empty: `locale.emptyText` = `EmptyState` (4.9), never the bare "No data".

### 4.7 `FactList`
antd `Descriptions` (`layout="vertical"`, `column={{ xs: 1, sm: 2 }}`, no border, label 12 ink-2, content 14–15/500), or a `<dl>` grid with the same look. Sections have small uppercase headings and a top divider (Document: Customer / {Type} / Record).

### 4.8 `Timeline`
antd `Timeline` with custom dots: approved = `CheckOutlined` on success-bg; submitted = `ArrowUpOutlined` on selected; rejected = `CloseOutlined` on danger-bg; returned = `RollbackOutlined` on warning-bg. Each item has a bold verb + "by {name}", the time as a caption, and any comment underneath.

### 4.9 `EmptyState`
antd `Empty` with a custom image (a 40 px icon), a title (16/600), one or two lines saying what being empty means, and the next action as a `Button`.

### 4.10 Facets and filter chips
- Facets: one `Checkbox.Group` per facet, inside a `fieldset` with a legend, with the counts aligned to the end; a `DatePicker.RangePicker` for Created.
- Applied filters appear as closable pills (`Tag closable` styled with `--jdms-selected`, close button labelled "Remove filter {name}") and a "Clear all" link.

### 4.11 `DocumentViewer`
The existing PDF.js canvas renderer, framed as in the designs: a header bar (file name · "Page x of n" · `ZoomOutOutlined`/`ZoomInOutlined` buttons with labels) over a sunken well containing the page (shadow from 2.2, max width 440 in the task pane, fluid on the document page).
- **Watermark overlay** for classified documents (keep the current rule for which levels): an absolutely positioned, `pointer-events: none` layer over the canvas, with repeated diagonal text "{user} · {time} · {classification}" at 12% navy. Caption under it: "Watermarked with your name and time".
- Loading: an antd `Skeleton.Image`. Error (for example quarantined): an inline `Alert type="error"` with the server message.

### 4.12 `DecisionBar` (Tasks)
Sticky at the bottom of the task detail, on `--jdms-footer`:
- An antd `Form` with `Input.TextArea` "Comment" and the help text "Required to reject or return".
- Buttons: **Approve** (`type="primary"`), **Return for changes** (default), **Reject** (`danger`, default style).
- Reject and Return validate that a comment is present, inline, without opening a dialog. The decision happens in place; this replaces the existing "confirm decision" dialog (manual figure 17).
- On success, show `message.success` and open the next task.

### 4.13 Interruption rules: which confirmation each action gets

| Action | Reversible? | Needs comment? | Pattern |
|---|---|---|---|
| Approve / Reject / Return a task | Reject final; Return restarts | Reject, Return: yes | In place (`DecisionBar`); no dialog |
| Delete a document | Yes (records officer restores) | No | **Inline confirm** (4.14) |
| Restore from recycle bin | Yes | No | Direct action + `message.success` |
| Place a legal hold | Yes (release) | Reason is a form field | Inline form (Records) |
| **Release a legal hold** | **No** (documents become disposable again) | **Yes** | **Modal** with a required reason |
| Prepare a disposition batch | Yes (reject) | No | Direct action |
| **Approve and destroy a batch** | **No** | **Yes** | **Modal** with a required comment + typing the document count |
| Reject a batch | Yes | Yes | Inline comment field on the batch row |
| Propose admin change / access rule | Yes (needs approval) | Optional | Inline form / own route |
| Approve / reject a pending change | Approve applies config (reversible by a new proposal) | Reject: yes | Inline on the row (comment field expands) |
| Check out / Check in | Yes | Check-in note optional | In place on the Versions tab |

### 4.14 `InlineConfirm`
For reversible destructive actions. An antd `Alert type="warning"` placed **in the page** where the action was started (for example under the document title). It holds the consequence sentence, a danger button with the verb ("Delete document"), and Cancel. Focus moves to the alert; Escape cancels. Not a Popconfirm, not a Modal.

---

## 5. Accessibility checklist (every screen)

- [ ] Skip link works and lands on `#main`.
- [ ] Real buttons, links and labelled inputs only (antd components already give this); no click handlers on `div`.
- [ ] Icon-only buttons have `aria-label`.
- [ ] Contrast at least 4.5:1 using section 2.1 pairs only, including 12 px captions and chips.
- [ ] Status and classification are never colour alone.
- [ ] Gold `:focus-visible` ring on every interactive element; antd focus glow off.
- [ ] Reduced motion respected (token `motion: false` + the CSS rule).
- [ ] The tab order follows the visual order in LTR and RTL.
- [ ] Tabs, tree, tables and timeline use antd's ARIA; the access matrix has row headers (`<th scope="row">`).
- [ ] Text expansion: test Arabic with the longest strings, with no truncation of buttons or chips (they wrap).
- [ ] Both languages checked at 1440 and 390 px.

---

## 6. Screens

Each section gives the route, roles, layout, data, behaviour, states and acceptance criteria. "Existing API" means the endpoint the current screen already calls through TanStack Query; keep it.

### 6.1 Home: `Home.html`, `HomeAr.html`, `*-mobile.png`
**Route** `/` · all roles.
1. `PageHeader`: "Good morning, {firstName}" (morning/afternoon/evening by local time; D1) with a summary line built from the counts. **Upload document** (`size="large"`, primary; makers only).
2. Four `StatCard`s: **Returned to you** (warning tone, "Amend and resubmit", goes to Tasks filtered), **Pending approval**, **Active**, **Rejected**. Each goes to Search with that status. Counts come from the status facet of the existing search API, scoped to the user (G11).
3. **Needs your action** (up to 4 tasks: "Passport · {ref}", "{kind} · {time}", and an action button by kind: Amend / Review / Vote) beside **Recent documents** (6 rows: Title + type, CIF, Status, Updated).

**Role variants:**
- Checker: tiles Waiting for you / Due soon / Returned by you; actions say "Review".
- Viewer: recent documents only.
- Nothing pending: `EmptyState` "You're all caught up".

**States:** `Skeleton` per card; an inline `Alert` with Retry per card on error.

**Acceptance**
- No horizontal scroll in Recent documents at 1440 px.
- Tile counts equal the Search facets.
- `HomeAr` mirrors correctly.

### 6.2 Tasks: `Tasks.html`
**Route** `/tasks`, `/tasks/:taskId`.
- `PageHeader` "Tasks" with a `Segmented` filter: All · n / Uploads · n / New versions · n / Due soon · n.
- **Split view:**
  - The list (max 420) has items with a kind caption, a due `StatusChip`, the title, and "From {maker} · {received}". Arrow keys move the selection.
  - The detail has a header (kind · round, title, submitter, due, "Open full document" link with `ExportOutlined`), then `DocumentViewer` beside a `FactList` of the type's fields, a **History** `Timeline`, and the `DecisionBar`.
  - Committee tasks show "Vote" and the vote progress (G4).
- **Phone:** the list is full width, and the detail is its own page with a back link.
- **Rules kept:** your own uploads never appear; Return starts a new round.

**Acceptance**
- Decide without leaving `/tasks`, with no dialogs.
- The next task opens after a decision.
- An empty inbox shows "No tasks waiting".
- The old Kind, Stage and Subject columns are gone.

### 6.3 Document page: `Document.html`
**Route** `/documents/:id`.

**Header**
- Breadcrumb (Cabinet / Type / CIF), then the title, then a meta row: `StatusChip`, `ClassificationLabel`, "Version {v}", "Updated {time}".
- A legal-hold `Alert type="warning"` above the title when held: "On legal hold: {hold}. It can't be deleted or disposed of."
- If checked out: an `Alert type="info"`, "Checked out by {name} since {time}", with **Check in** for that user.

**Actions** (end), by state and permission:

| State | Primary | Secondary | More menu |
|---|---|---|---|
| ACTIVE | Download | New version, Check out | Copy link, Delete… |
| Checked out by me | Check in | Download | Cancel check-out, Copy link |
| PENDING_APPROVAL / DRAFT | Download (if allowed) | (makers on a DRAFT: Edit and resubmit) | Copy link |
| DELETED (records officer) | **Restore** (`RollbackOutlined`) | — | Copy link |
| DISPOSED | — | — | Copy link |

**Delete:** an `InlineConfirm` under the header reading "Delete this document? It moves to the recycle bin; only a records officer can restore it.", with [Delete document] [Cancel].

**Tabs:** **Overview** · Versions · {n} · Approval history · Audit (audit only for roles with VIEW_AUDIT, or per the current rule).
- **Overview:** `DocumentViewer` (flex 3) beside the facts column (flex 2): a `FactList` with Customer (name, CIF linked to a CIF search) / {Type} fields / Record (Branch, Created, Retention policy or "None", Legal hold or "None"), plus an **Approval** `Timeline`.
- **Versions:** a table (Version, Uploaded by, Time, Status, Note, Download); the current version is tagged; Check out / Check in live here as well.
- **Approval history:** the full `Timeline` with comments.
- **Audit:** the Audit table (6.10) filtered to this document, without the chain banner.
- **DISPOSED:** no viewer. Show an `EmptyState`: "This document was destroyed on {date} under {policy}. A record that it existed is kept." Facts remain.

**Acceptance**
- The preview shows without changing tabs.
- Delete is in More and confirms in place.
- Restore appears only on DELETED for records officers.
- The watermark covers classified previews.

### 6.4 Upload: `Upload.html`
**Route** `/upload` · maker.

An antd `Form layout="vertical"` (flex 3) beside the summary rail (flex 1, sticky on desktop, below the form on a phone). Numbered `Card`s:
1. **File**: `Upload.Dragger` first. After a file is chosen, a file row (icon, name, size · pages · ready, Replace).
2. **What is it?**: Cabinet*, Document type*. Choosing the type rebuilds sections 3–4.
3. **Customer and {type}**: the required standard fields, then the type's fields in definition order, rendered by type:

| Field type | Component |
|---|---|
| Text (max length) | `Input` with `maxLength` and `showCount` above 100 characters |
| Number | `InputNumber`, full width, locale-formatted |
| Date | `DatePicker`, format `D MMM YYYY` |
| Yes/No | `Radio.Group` Yes / No (not a Switch: nothing happens until submit) |
| List | `Select` (`showSearch` above 7 options) |

4. **Filing and access**:
   - Title (default "{Type} {customer name}").
   - Branch* (defaults to the user's branch).
   - **Classification*** as a `Select` whose options show the `ClassificationLabel`, limited to levels at or above the type minimum, which is the default.
   - Folder.
   - A `Collapse` (closed) for **More references**: account, collateral, contract, transaction, application.

**Rail "Before you submit"**
- A live checklist: file attached; "{n} of {m} required fields filled"; type validations.
- **Approval route** in words from the type definition, plus "You can't approve your own upload."
- **Submit for approval** (primary; "Upload" when the type has no approval) and **Save as draft** (G5).

**Validation:** antd `Form` rules on blur and submit; `scrollToFirstError`; server errors mapped to fields.

### 6.5 Search: `Search.html`
**Route** `/search?q=&scope=&status=&type=&cabinet=&branch=&from=&to=&sort=&page=`. The URL holds all state.

- **Header:** "{n} results for "{q}"" with the description "Searched titles, details and document text (including scans), in the branches and classifications you can see."
- **Scope** `Segmented`: **Everything** / **CIF** (`scope=cif`). This replaces the separate CIF box. The header search always submits `scope=all`. When `q` has no spaces and no results, show a link: "Search {q} as a CIF instead".
- Sort `Select` (Best match / Newest first / Title A–Z) and a list/table `Segmented` (icons with labels).
- **Refine** (max 280): facets for Status, Document type, Cabinet, Branch, and a Created range; "Clear all".
- **Results:**
  - Filter pills and "Showing 1–20 of {n}".
  - Result cards: thumbnail 48×64, title link (16/600), `StatusChip`, `ClassificationLabel` (only above the type minimum), a labelled meta row ("Passport · CIF **…** · Branch **…** · Created **…**"), and a snippet with server highlights rendered as `<mark>`.
  - **Preview** expands an inline `DocumentViewer` panel under that result (in place, not a drawer). Only one panel is open at a time.
  - Table view uses the Browse columns.

**Acceptance**
- Labelled meta.
- Checkbox facets: values in one group combine with OR, groups combine with AND.
- The URL reproduces the view.
- No drawer.

### 6.6 Browse: `Browse.html`
**Route** `/browse/:cabinet?/:folderId?`.
- `PageHeader`: breadcrumb, cabinet name, and **Upload here** (makers; opens `/upload?cabinet=&folder=`).
- **Three panes:**
  1. Cabinet `Select` + `Tree` (`showIcon`, counts in titles).
  2. Toolbar (`Input` "Filter by title or CIF", type `Select`, status `Select`) + `Table` (Title + type, CIF, Status, Updated; sortable) + pagination. Click a row to select it; double-click or Enter opens it.
  3. **Properties** (max 320): thumbnail, title, chip + classification, `FactList`, **Open** (primary) + Download icon button.
- Below 1100 px the properties pane is hidden, and selecting a row opens the document.

### 6.7 Records: `Records.html`
**Route** `/records` (`#holds`, `#retention`, `#disposition`) · records officer.
1. **Three StatCards** that jump to their sections: Retention policies (0 → warning tone, "Nothing will ever become due"), Active legal holds, Disposition batches.
2. **Legal holds**
   - **New legal hold** expands an **inline form** at the top of the card (Name, Reason, "Hold all documents of CIF") with Place hold / Cancel.
   - Each hold row has `PauseCircleOutlined`, the name, `StatusChip` Holding, the reason, "{n} documents · placed {time} by {name}" (G6), **View documents** (G7) and **Release…**, which opens a **Modal** with a required reason: "Release this hold? {n} documents become subject to their retention again and may be destroyed." [Release hold].
3. **Retention policies**
   - Table: Name, Starts from, Period (as years and months), At the end, Document types (tags).
   - **New policy** expands an inline form in the card.
   - Empty: the `EmptyState` in the design, with "Create the first policy".
4. **Disposition**
   - A 3-step explainer and **List documents due**, then a batch table (Prepared time and by, `StatusChip`, document count, actions).
   - The second officer sees **Approve and destroy…**, a **Modal** with a required comment that asks them to type the document count before the danger button enables. They can also **Reject**, which opens an inline comment field on the row.
   - The officer who prepared the batch sees "Waiting for a second records officer".

### 6.8 Administration: document types: `AdminTypes.html`
**Routes** `/admin/types`, `/admin/types/:code`, `/admin/types/new`, `/admin/types/:code/propose` · tenant admin.
- `PageHeader` with the description "Each type decides its fields, classification and approval. Every change needs a second administrator." and **New document type**, which goes to `/admin/types/new`: its own page, not a drawer.
- **List** (flex 3):
  - Toolbar: filter input, cabinet `Select`, and the `Checkbox` **Hide test types ({n})**, ticked by default. It hides codes with a configurable prefix (`E2E_`); the checkbox is hidden entirely when the count is 0.
  - Table: Type (name 600 + `.jdms-code`), Cabinet, Classification (`ClassificationLabel`), Approval, Status, and a "Pending" `Tag` when a proposal is open (G8).
- **Detail** (flex 2): the selected type's facts, a **Fields** list (label · Arabic label, kind, Required/Optional tag), **Rules** (three on/off values), and **Propose change**, which goes to the propose route.
- **Propose / new form** (own page, same layout as the current form, regrouped into Cards):
  - Basics: cabinet, code, category, names in English and Arabic.
  - Rules: minimum classification, approval, channels, required standard fields, three `Switch`es, which take effect only after approval (the Switch is acceptable because it's inside a form submitted with Propose).
  - Fields: an editable list, where **Add field** adds an inline row.
  - Submit **Propose** leads to `message.success`: "Proposed; another administrator must approve it."
- **Pending changes** (`/admin/changes`, shared with security admin): a table (change, subject, operation, proposed by and at). **Approve** acts directly; **Reject** expands an inline comment field on the row. Your own proposals show "Waiting for another administrator". Empty: "No changes waiting for approval."

### 6.9 Administration: access rules: `AccessRules.html`
**Route** `/admin/access` · security admin.
- `PageHeader` with the description and **Propose a rule**, which expands an inline form above the matrix (role or user, actions, scope, Allow/Deny, description).
- **Role × action matrix** for whole-bank rules:
  - Grouped column headers: Documents (7), Workflow (1), Records (4), Administration (4).
  - Cells are 24 px squares: Allow = action-blue with a white `CheckOutlined` (`aria-label="Allowed"`); Deny = danger with `CloseOutlined` (`aria-label="Denied"`); not granted = sunken.
  - Clicking a cell opens a **detail panel inline below the matrix** (rule source and description, Propose change).
  - The role column is sticky; the matrix scrolls sideways inside its card.
  - Column mapping:

| Column | Action |
|---|---|
| View details | VIEW_METADATA |
| View file | VIEW_CONTENT |
| Download | DOWNLOAD |
| Upload | UPLOAD |
| Edit details | EDIT_METADATA |
| Check out | CHECK_OUT |
| Delete | DELETE |
| Approve | APPROVE |
| Restore | RESTORE |
| Legal holds | MANAGE_HOLDS |
| Retention | MANAGE_RETENTION |
| Dispose | DISPOSE |
| Doc types | ADMIN_METADATA |
| Access | ADMIN_ACCESS |
| Audit | VIEW_AUDIT |
| Import | IMPORT |

  Unknown actions go into an "Other" column labelled with the code.
- **Narrower rules:** a list with a scope tag, the target, a sentence ("Viewer may view details and file"), an Allow/Deny chip and the description, under the note "A Deny here always wins."

**Acceptance:** the matrix reproduces Testing Guide table 3.1 exactly.

### 6.10 Audit trail: `Audit.html`
**Route** `/audit` · security admin.
1. `PageHeader` and **Export results** (G9; hidden until the endpoint exists).
2. **Chain banner:**
   - Intact: `SafetyCertificateOutlined`, "Chain intact", "All {n} events verified on {time}. No gaps or altered entries.", **Verify now**.
   - Broken: danger, "Chain broken at event {seq}", a link to it.
   - Verifying: `Spin`.
3. **Filters**, one wrapping row that applies on change (debounced 300 ms) and lives in the URL: "Who or what" `Input`, Action `Select` (display names), Period `Select` (24 h / 7 days / custom `RangePicker`), and an Outcome `Segmented` (All / Success / Denied / Error).
4. **Table:** #, Time, Event (name + `.jdms-code`), By (name + short ID, G10), On (resource type + short ID), Outcome `StatusChip`. `expandable` rows show the full actor, resource and request IDs (copyable) and "Follows event {n} · hash verified".

**Event names:**

| Code | Display name |
|---|---|
| AUDIT_VERIFIED | Audit chain verified |
| AUDIT_QUERIED | Audit trail searched |
| APPROVAL_STARTED | Approval started |
| APPROVAL_DECIDED | Approval decided |
| APPROVAL_APPROVED | Approval completed |
| CHANGE_PROPOSED | Change proposed |
| CHANGE_APPROVED | Change approved |
| DOCUMENT_VIEWED | Document viewed |
| DOCUMENT_DELETED | Document deleted |

Unknown codes fall back to the code in sentence case.

### 6.11 Sign-in (Keycloak login theme): `SignIn.html`
Not React. It's a Keycloak **login theme**, `themes/jdms/login/`:
- `theme.properties` (`parent=keycloak.v2`, `locales=en,ar`)
- `resources/css/jdms.css`
- **self-hosted** Plex fonts (copied from the @fontsource files; OFL-1.1, keep the licence file next to them)
- `messages/messages_en.properties` and `messages_ar.properties`
- `login.ftl` / `template.ftl` overrides for the split layout

Register it as the login theme in the `jdms` realm import for the local profile.

- **Brand panel** (navy):
  - The wordmark (LTR).
  - An environment tag, shown only when a theme property such as `envLabel` is set ("LOCAL DEVELOPMENT").
  - The headline "Secure, bilingual document management for banks" at 40/600, with the Arabic line under it.
  - Three points with gold icons: Encrypted per file · Every action audited · Maker-checker approvals.
- **Form panel:**
  - Bank display name and the English/العربية locale switch.
  - "Sign in", then "Use your bank network account."
  - Username, Password with show/hide, a full-width primary **Sign in** (48 h), and a help line ("Contact {help desk}" from a message property).
- The two halves stack below 900 px. Arabic is RTL through Keycloak's `dir` and logical CSS.
- Skip link and focus ring as in the app. Reduced-motion CSS included.

### 6.12 Screens not drawn (apply the system)
- **Recycle bin:** `PageHeader` + `Table` (Title + type, CIF, Branch, Deleted at, Deleted by) + **Restore** per row + `EmptyState` "The recycle bin is empty".
- **Notifications:** a `Dropdown` panel from the bell, listing items (icon, sentence, time, link) with "Mark all as read".

---

## 7. Testing

**Vitest + Testing Library**
- `buildNav(roles)` returns the right items per role.
- `StatusChip` renders all 6 document statuses with the right text and marker class.
- `ClassificationLabel` renders the 4 levels, and lists hide it at or below the type minimum.
- Formatters give the expected output in `en` and `ar`.
- The matrix builder reproduces the baseline rules.
- `DecisionBar` blocks Reject and Return without a comment.

**Playwright:** update these existing expectations.
- The Home greeting ("Welcome, Mona Maker" → "Good morning, Mona", or keep "Welcome" per D1).
- Menu checks now target the side `Menu` (same item sets per role).
- The task drawer becomes a split view; decisions no longer open a confirm dialog.
- The Viewer tab is merged into Overview.
- The separate CIF box is gone (use the `scope=cif` Segmented).
- Delete confirms inline, not in a dialog.
- Audit filters apply without a Search button.

**Add:**
- Skip link.
- Keyboard-only approval in Tasks.
- RTL smoke test per screen (`dir=rtl`, sider on the right, wordmark LTR).
- Reduced motion (`page.emulateMedia({ reducedMotion: 'reduce' })`).
- axe scan per screen with zero serious violations (use `@axe-core/playwright` if it's already allowed; otherwise raise it, since it's MPL-2.0).

**Visual:** compare each screen with `designs/png/<Screen>.png` at 1440 px; check 390 px and Arabic.

---

## 8. Delivery plan (one PR per phase)

| Phase | Scope | Done when |
|---|---|---|
| 1. Foundations | `theme.ts` + `index.css` tokens (2.3–2.5), `AppShell` with skip link and role menu, RTL helpers, formatters, icon mapping, `StatusChip`, `ClassificationLabel`, `PageHeader`, `EmptyState`, `InlineConfirm` | Every existing screen renders in the new shell in EN/AR; nav per role matches 3.1; unit tests pass |
| 2. Daily work | Home, Tasks (split view + `DecisionBar`), Document page (tabs, viewer frame + watermark, check-out/in, delete inline, restore, disposed view) | 6.1–6.3 acceptance; Playwright updated |
| 3. Capture and find | Upload (field-type mapping), Search (scope, inline preview), Browse | 6.4–6.6 acceptance |
| 4. Governance | Records (inline forms, two modals), Recycle bin, Document types + propose route + Pending changes, Access matrix, Audit | 6.7–6.10, 6.12 acceptance |
| 5. Sign-in | Keycloak login theme, self-hosted fonts | Branded login in EN/AR on the local profile |
| 6. Polish | axe fixes, phone-width pass, Arabic copy review, reduced-motion check | Section 5 checklist complete |

---

## 9. Backend gaps and decisions

Implement the fallback now and mark it `TODO(backend-gap: Gx)`.

| ID | Needed by | Gap | Fallback |
|---|---|---|---|
| G1 | Header, sign-in | Bank display name | Tenant code |
| G2 | Lists, facts | Branch names | Branch code |
| G3 | Lists, search | A document type's minimum classification in list/search responses | Hide classification in lists |
| G4 | Tasks | Committee vote progress | Hide the line |
| G5 | Upload | Save as draft from upload | Omit the button |
| G6 | Records | Who placed or released a hold | Time only |
| G7 | Records → Search | Filter by legal hold | Link to a CIF search for CIF holds; otherwise hide |
| G8 | Admin types | Open-proposal flag per type | No tag |
| G9 | Audit | CSV export | Hide Export |
| G10 | Audit | Actor display names | Short ID only |
| G11 | Home | Per-user status counts | Search facet counts |

**Decisions for the product owner**
- **D1:** Home greeting: "Good morning, {name}" (design) or keep "Welcome, {name}" (current tests).
- **D2:** Digits in Arabic: Western (design) or Arabic-Indic.
- **D3:** Final Arabic wording for roles, statuses, classifications and document types (3.3).
- **D4:** Whether the Recycle bin stays a top-level item for records officers or moves under Records.
- **D5:** Which classification levels get the viewer watermark (keep the current rule unless the bank says otherwise).
