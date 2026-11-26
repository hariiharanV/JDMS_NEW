import { test as base, expect, type BrowserContext, type Page } from '@playwright/test';
import { choose, decide, openTask, pdf, signIn, unique, uploadPassport } from './support';

/** `as(user)`: a signed-in page in its own browser context; the test's contexts are closed when it ends. */
const test = base.extend<{ as: (username: string, language?: 'en' | 'ar') => Promise<Page> }>({
  as: async ({ browser }, provide) => {
    const contexts: BrowserContext[] = [];
    await provide(async (username, language = 'en') => {
      const context = await browser.newContext();
      contexts.push(context);
      const page = await context.newPage();
      await signIn(page, username, language);
      return page;
    });
    await Promise.all(contexts.map((context) => context.close()));
  },
});

async function statusOf(page: Page, id: string): Promise<string> {
  await page.goto(`/documents/${id}`);
  return (await page.locator('.jdms-page-meta .jdms-chip').first().textContent()) ?? '';
}

test('maker uploads with the dynamic form; checker approves, rejects and returns from the inbox', async ({ as }) => {
  const maker = await as('maker1');
  const checker = await as('checker1');
  const cif = unique('C');
  const approved = await uploadPassport(maker, cif, `Passport ${cif} A`);
  await expect(maker.locator('.jdms-page-header')).toContainText('Pending approval');
  const rejected = await uploadPassport(maker, cif, `Passport ${cif} R`);
  const returned = await uploadPassport(maker, cif, `Passport ${cif} T`);

  await decide(checker, `Passport ${cif} A`, 'Approve');
  await decide(checker, `Passport ${cif} R`, 'Reject', 'Photo page unreadable');
  await decide(checker, `Passport ${cif} T`, 'Return for changes', 'Add the expiry page');

  expect(await statusOf(maker, approved)).toBe('Active');
  expect(await statusOf(maker, rejected)).toBe('Rejected');
  expect(await statusOf(maker, returned)).toBe('Draft');
  await maker.getByRole('tab', { name: 'Approval history' }).click();
  await expect(maker.getByRole('tabpanel', { name: 'Approval history' }).getByText('Add the expiry page')).toBeVisible();
});

test('browse, PDF.js preview and a new version', async ({ as }) => {
  const maker = await as('maker1');
  const checker = await as('checker1');
  const cif = unique('V');
  const id = await uploadPassport(maker, cif, `Passport ${cif}`);
  await decide(checker, `Passport ${cif}`, 'Approve');

  // three panes: cabinet tree, list, properties
  await maker.goto('/browse');
  await maker.getByRole('textbox', { name: 'Filter by CIF' }).fill(cif);
  await maker.getByRole('textbox', { name: 'Filter by CIF' }).press('Enter');
  await maker.getByRole('cell', { name: cif, exact: true }).click();
  await expect(maker.getByRole('region', { name: 'Properties' })).toContainText(`Passport ${cif}`);

  // the worker renders the preview; the overview shows it as soon as processing is done, beside the facts
  await maker.goto(`/documents/${id}`);
  await expect(maker.getByTestId('pdf-canvas')).toBeVisible({ timeout: 120_000 });
  await expect(maker.getByRole('tabpanel', { name: 'Overview' }).getByText(cif).first()).toBeVisible();
  await expect(maker.getByText('Page 1 of 1')).toBeVisible();
  const painted = await maker.getByTestId('pdf-canvas').evaluate((canvas: HTMLCanvasElement) => {
    const context = canvas.getContext('2d');
    const pixels = context?.getImageData(0, 0, canvas.width, canvas.height).data ?? new Uint8ClampedArray();
    return pixels.some((value, index) => index % 4 !== 3 && value < 128);
  });
  expect(painted).toBe(true);

  await maker.getByRole('button', { name: 'New version' }).click();
  const modal = maker.getByRole('region', { name: 'New version' });
  await modal.getByRole('textbox').fill('Renewed passport');
  await modal.locator('input[type=file]').setInputFiles({ name: 'renewed.pdf', mimeType: 'application/pdf',
    buffer: pdf(`JDMS e2e renewed ${cif}`) });
  await modal.getByRole('button', { name: 'Check in' }).click();
  await expect(maker.getByText('New version uploaded')).toBeVisible();
  await maker.getByRole('tab', { name: /^Versions · 2/ }).click();
  await expect(maker.getByRole('cell', { name: /^2\.0/ })).toBeVisible();
  await expect(maker.getByRole('cell', { name: 'Renewed passport' })).toBeVisible();
});

test('search with facets', async ({ as }) => {
  const maker = await as('maker1');
  const checker = await as('checker1');
  const cif = unique('S');
  await uploadPassport(maker, cif, `Passport ${cif}`);
  await decide(checker, `Passport ${cif}`, 'Approve');

  // the CIF scope searches the customer number; the URL holds the view
  await maker.goto('/search');
  await maker.locator('.jdms-search-bar .ant-segmented').getByText('CIF', { exact: true }).click();
  await expect(maker.getByRole('radio', { name: 'CIF' })).toBeChecked();
  await expect(async () => {
    await maker.getByRole('searchbox', { name: 'Search text' }).fill(cif);
    await maker.getByRole('searchbox', { name: 'Search text' }).press('Enter');
    await expect(maker.getByRole('heading', { level: 1, name: `1 result for CIF ${cif}` })).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 60_000, intervals: [2_000] });
  await expect(maker).toHaveURL(new RegExp(`scope=cif.*q=${cif}|q=${cif}.*scope=cif`));
  await expect(maker.getByRole('link', { name: `Passport ${cif}` })).toBeVisible();

  const typeFacet = maker.getByRole('group', { name: 'Document type' });
  await typeFacet.getByRole('checkbox', { name: /^Passport/ }).click();
  await expect(typeFacet.getByRole('checkbox', { name: /^Passport/ })).toBeChecked();
  await expect(maker.getByRole('button', { name: /^Remove filter Document type: Passport/ })).toBeVisible();
  await maker.getByRole('group', { name: 'Status' }).getByRole('checkbox', { name: /^Active/ }).click();
  await expect(maker.getByRole('group', { name: 'Status' }).getByRole('checkbox', { name: /^Active/ })).toBeChecked();
  await expect(maker.getByRole('link', { name: `Passport ${cif}` })).toBeVisible();
  await expect(maker).toHaveURL(/status=ACTIVE/);

  // the preview opens in place under the result, not in a drawer
  await maker.getByRole('button', { name: 'Preview' }).first().click();
  await expect(maker.getByTestId('pdf-canvas')).toBeVisible({ timeout: 60_000 });
  await expect(maker.getByRole('dialog')).toHaveCount(0);
});

test('deletion with approval, then restore from the recycle bin', async ({ as }) => {
  const maker = await as('maker1');
  const checker = await as('checker1');
  const records = await as('records1');
  const cif = unique('D');
  const id = await uploadPassport(maker, cif, `Passport ${cif}`);
  await decide(checker, `Passport ${cif}`, 'Approve');

  // delete sits in More and confirms in the page, not in a dialog
  await maker.goto(`/documents/${id}`);
  await maker.getByRole('button', { name: 'More actions' }).click();
  await maker.getByRole('menuitem', { name: 'Delete…' }).click();
  await expect(maker.getByRole('dialog')).toHaveCount(0);
  await maker.getByRole('button', { name: 'Delete document' }).click();
  await expect(maker.getByText('Deletion done or sent for approval')).toBeVisible();
  await decide(checker, `Passport ${cif}`, 'Approve');
  expect(await statusOf(maker, id)).toBe('Deleted');

  await records.goto('/recycle-bin');
  const row = records.getByRole('row', { name: new RegExp(cif) });
  await row.getByRole('button', { name: 'Restore' }).click();
  await expect(records.getByText('Document restored')).toBeVisible();
  await expect(records.getByRole('row', { name: new RegExp(cif) })).toHaveCount(0);
  expect(await statusOf(maker, id)).toBe('Active');
});

test('an administrator proposes a document type with fields; a second one approves it', async ({ as }) => {
  const admin1 = await as('tenantadmin1');
  const admin2 = await as('tenantadmin2');
  const code = unique('E2E_');

  // a new type is its own page, not a drawer
  await admin1.goto('/admin');
  await admin1.getByRole('link', { name: 'New document type' }).click();
  await expect(admin1).toHaveURL(/\/admin\/types\/new$/);
  const modal = admin1.getByRole('region', { name: 'New document type' });
  await expect(modal).toBeVisible();
  await choose(admin1, 'Cabinet', 'Customers');
  await modal.getByLabel('Code', { exact: true }).fill(code);
  await modal.getByLabel('Category', { exact: true }).fill('TEST');
  await modal.getByLabel('Name (English)').fill(`E2E ${code}`);
  await modal.getByLabel('Name (Arabic)').fill('نوع اختبار');
  await modal.getByRole('button', { name: 'Add field' }).click();
  const field1 = modal.getByRole('group', { name: 'Field 1' });
  await field1.getByLabel('Key').fill('reference_no');
  await field1.getByLabel('Label (English)').fill('Reference number');
  await field1.getByLabel('Label (Arabic)').fill('الرقم المرجعي');
  await field1.getByRole('switch').click();
  await modal.getByRole('button', { name: 'Add field' }).click();
  const field2 = modal.getByRole('group', { name: 'Field 2' });
  await field2.getByLabel('Key').fill('nationality');
  await field2.getByLabel('Label (English)').fill('Nationality');
  await field2.getByLabel('Label (Arabic)').fill('الجنسية');
  await choose(admin1, 'Type', /^List$/, field2);
  await choose(admin1, 'List of values', 'Nationality', field2);
  await modal.getByRole('button', { name: 'Propose' }).click();
  await expect(admin1.getByText('Proposed; another administrator must approve it')).toBeVisible();

  // the proposer cannot decide their own change
  await admin1.getByRole('navigation').getByRole('link', { name: 'Pending changes' }).click();
  const ownRow = admin1.getByRole('row', { name: new RegExp(code) });
  await expect(ownRow).toBeVisible();
  await expect(ownRow.getByRole('button', { name: 'Approve' })).toHaveCount(0);

  await admin2.goto('/admin');
  await admin2.getByRole('navigation').getByRole('link', { name: 'Pending changes' }).click();
  await admin2.getByRole('row', { name: new RegExp(code) }).getByRole('button', { name: 'Approve' }).click();
  await expect(admin2.getByText('Decision recorded')).toBeVisible();
  await admin2.getByRole('navigation').getByRole('link', { name: 'Document types' }).click();
  // test types are hidden by default
  await expect(admin2.getByText(code, { exact: true })).toHaveCount(0);
  await admin2.getByRole('checkbox', { name: /^Hide test types/ }).uncheck();
  await admin2.getByText(code, { exact: true }).click();
  await expect(admin2).toHaveURL(new RegExp(`/admin/types/${code}$`));
  await expect(admin2.getByRole('region', { name: 'Document type details' })).toContainText('Reference number');
});

test('governance: a legal hold placed and released, the access matrix and the audit filters', async ({ as }) => {
  const records = await as('records1');
  const name = unique('Hold ');
  await records.goto('/records');
  await records.getByRole('button', { name: 'New legal hold' }).click();
  const form = records.getByRole('region', { name: 'New legal hold' });
  await form.getByLabel('Name', { exact: true }).fill(name);
  await form.getByLabel('Reason', { exact: true }).fill('E2E: court case');
  await form.getByRole('button', { name: 'Place hold' }).click();
  await expect(records.getByText('Legal hold placed')).toBeVisible();
  const hold = records.getByRole('listitem').filter({ hasText: name });
  await hold.getByRole('button', { name: 'Release…' }).click();
  // releasing cannot be undone: the only kind of action that opens a dialog
  const dialog = records.getByRole('dialog', { name: 'Release this hold?' });
  await dialog.getByRole('button', { name: 'Release hold' }).click();
  await expect(records.getByText('Legal hold released')).toBeVisible();
  await expect(hold).toContainText('Released');

  const security = await as('secadmin1');
  await security.goto('/admin/access');
  await expect(security.getByRole('rowheader', { name: 'Maker' })).toBeVisible();
  await expect(security.getByRole('button', { name: 'Maker, Upload: Allowed' })).toBeVisible();
  await expect(security.getByRole('button', { name: 'Viewer, Download: Not allowed' })).toBeVisible();
  await security.getByRole('button', { name: 'Checker, Approve: Allowed' }).click();
  await expect(security.getByRole('region', { name: 'Rule details' })).toContainText('Baseline: CHECKER');

  // the audit filters apply as they change and live in the URL
  await security.goto('/audit');
  await security.locator('.jdms-filter-outcome').getByText('Success', { exact: true }).click();
  await expect(security).toHaveURL(/outcome=SUCCESS/);
  await expect(security.locator('.ant-table-row').first()).toBeVisible();
});

test('the UI hides what a user cannot do', async ({ as }) => {
  const maker = await as('maker1');
  const checker = await as('checker1');
  const viewer = await as('viewer1');
  const cif = unique('H');
  const id = await uploadPassport(maker, cif, `Passport ${cif}`);

  const nav = viewer.getByRole('navigation');
  await expect(nav.getByText('Search')).toBeVisible();
  for (const hidden of ['Upload', 'Recycle bin', 'Records', 'Document types', 'Pending changes', 'Access rules', 'Audit']) {
    await expect(nav.getByText(hidden, { exact: true })).toHaveCount(0);
  }

  // the checker may read and download but not change or delete the maker's document
  await checker.goto(`/documents/${id}`);
  await expect(checker.getByRole('button', { name: 'Download' })).toBeVisible();
  await expect(checker.getByRole('button', { name: 'New version' })).toHaveCount(0);
  await checker.getByRole('button', { name: 'More actions' }).click();
  await expect(checker.getByRole('menuitem', { name: 'Copy link' })).toBeVisible();
  await expect(checker.getByRole('menuitem', { name: /Delete/ })).toHaveCount(0);
  // the maker may not delete while it waits for approval either; once approved, Delete appears (journey above)
  await maker.getByRole('button', { name: 'More actions' }).click();
  await expect(maker.getByRole('menuitem', { name: 'Copy link' })).toBeVisible();
  await expect(maker.getByRole('menuitem', { name: /Delete/ })).toHaveCount(0);
});

test('keyboard: move through the inbox with the arrow keys and approve without the mouse', async ({ as }) => {
  const maker = await as('maker1');
  const checker = await as('checker1');
  const cif = unique('K');
  const id = await uploadPassport(maker, cif, `Passport ${cif}`);

  const detail = await openTask(checker, `Passport ${cif}`);
  const current = checker.url();
  const link = checker.getByRole('region', { name: 'Task list' }).locator('a[aria-current="true"]');
  await link.focus();
  const neighbour = (await link.evaluate((element) => !!element.closest('li')?.nextElementSibling)) ? 'ArrowDown' : 'ArrowUp';
  await checker.keyboard.press(neighbour);
  await expect(checker).not.toHaveURL(current);
  await checker.keyboard.press(neighbour === 'ArrowDown' ? 'ArrowUp' : 'ArrowDown');
  await expect(checker).toHaveURL(current);
  await expect(detail.getByRole('heading', { level: 2, name: new RegExp(cif) })).toBeVisible();

  // Reject without a comment stops at the comment field; then approve from the keyboard
  await detail.getByRole('button', { name: 'Reject', exact: true }).focus();
  await checker.keyboard.press('Enter');
  await expect(detail.getByRole('textbox', { name: 'Comment' })).toBeFocused();
  await expect(detail.getByRole('alert')).toContainText('Write why you are rejecting it');
  await checker.keyboard.press('Tab');
  await expect(detail.getByRole('button', { name: 'Approve', exact: true })).toBeFocused();
  await checker.keyboard.press('Enter');
  await expect(checker.getByText('Decision recorded')).toBeVisible();
  await expect.poll(() => statusOf(maker, id), { timeout: 30_000 }).toBe('Active');
});

test('keyboard: skip link, menu and visible focus', async ({ as }) => {
  const maker = await as('maker1');
  await maker.keyboard.press('Tab');
  const skip = maker.getByRole('link', { name: 'Skip to main content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await maker.keyboard.press('Enter');
  await expect(maker.locator('#main')).toBeFocused();

  await maker.goto('/search');
  const query = maker.getByRole('searchbox', { name: 'Search text' });
  await query.focus();
  await maker.keyboard.type('passport');
  await maker.keyboard.press('Enter');
  await expect(maker.getByRole('heading', { level: 1, name: /results? for “passport”/ })).toBeVisible();
});

test('every screen renders right to left in Arabic', async ({ as }, testInfo) => {
  const screens: { user: string; paths: string[] }[] = [
    { user: 'maker1', paths: ['/', '/browse', '/upload', '/search', '/tasks'] },
    { user: 'records1', paths: ['/recycle-bin', '/records'] },
    { user: 'tenantadmin1', paths: ['/admin'] },
    { user: 'secadmin1', paths: ['/audit'] },
  ];
  for (const { user, paths } of screens) {
    const page = await as(user, 'ar');
    for (const path of paths) {
      await page.goto(path);
      await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(page.locator('.ant-spin-spinning')).toHaveCount(0);
      // navigation reads right to left: the first item sits at the right edge
      const nav = await page.getByRole('navigation').getByRole('link').first().boundingBox();
      const viewport = page.viewportSize();
      expect(nav && viewport && nav.x > viewport.width / 2).toBe(true);
      await testInfo.attach(`ar${path.replace(/\//g, '-') || '-home'}`, {
        body: await page.screenshot({ fullPage: true }), contentType: 'image/png',
      });
    }
  }
  // a document screen too
  const maker = await as('maker1', 'ar');
  await maker.goto('/browse');
  await maker.locator('.jdms-browse-list .ant-table-tbody a').first().click();
  await expect(maker.getByRole('tab', { name: 'نظرة عامة' })).toBeVisible();
  await testInfo.attach('ar-document', { body: await maker.screenshot({ fullPage: true }), contentType: 'image/png' });
});
