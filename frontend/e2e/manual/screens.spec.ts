import { test, expect, type Browser, type Page } from '@playwright/test';
import { choose, decide, openTask, pdf, search, signIn, typeDate, unique } from '../stack/support';

const OUT = process.env.SHOTS ?? 'shots';
const log: string[] = [];

/** Takes a screenshot once the page has settled: no spinner, no skeleton, no animation running. */
async function shot(page: Page, name: string, fullPage = false) {
  await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('.ant-skeleton')).toHaveCount(0, { timeout: 30_000 });
  await page.waitForTimeout(700);
  // a whole long form: the window grows to the page's height, so sticky panels stay where the user sees them
  const viewport = page.viewportSize();
  if (fullPage && viewport) {
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width: viewport.width, height });
    await page.waitForTimeout(500);
  }
  await page.screenshot({ path: `${OUT}/${name}.png` });
  if (fullPage && viewport) await page.setViewportSize(viewport);
  log.push(name);
}

/** A failed step is reported and the walk-through goes on, so one changed screen does not cost every other shot. */
async function step(name: string, fn: () => Promise<void>) {
  try {
    await fn();
  } catch (e) {
    console.log(`STEP FAILED ${name}: ${String(e).slice(0, 300)}`);
  }
}

async function user(browser: Browser, name: string, language: 'en' | 'ar' = 'en', width = 1440): Promise<Page> {
  const context = await browser.newContext({ viewport: { width, height: width < 600 ? 844 : 900 } });
  const page = await context.newPage();
  await signIn(page, name, language);
  return page;
}

async function fillPassport(page: Page, cif: string, title: string, customer: string) {
  await page.goto('/upload');
  await choose(page, 'Cabinet', 'Customers');
  await search(page, 'Document type', 'Passport');
  await page.getByLabel('Title', { exact: true }).fill(title);
  await page.getByLabel('CIF', { exact: true }).fill(cif);
  const name = page.getByLabel('Customer name', { exact: true });
  if (await name.count()) await name.fill(customer);
  await typeDate(page, 'Expiry date', '2031-05-31');
  await page.getByLabel('Passport number', { exact: true }).fill(`P${cif.slice(-7)}`);
  await search(page, 'Nationality', 'Oman');
  await typeDate(page, 'Issue date', '2021-06-01');
  await page.locator('input[type=file]').setInputFiles({ name: `${title}.pdf`, mimeType: 'application/pdf',
    buffer: pdf(`Passport data page - ${customer} - ${cif}`) });
}

async function upload(page: Page): Promise<string> {
  await page.getByRole('button', { name: 'Upload', exact: true }).click();
  await page.waitForURL(/\/documents\/[0-9a-f-]{36}$/);
  return page.url().split('/').pop() ?? '';
}

/** Opens the document and waits until the worker's preview is painted beside its details. */
async function openWithPreview(page: Page, id: string) {
  await expect(async () => {
    await page.goto(`/documents/${id}`);
    await expect(page.getByTestId('pdf-canvas')).toBeVisible({ timeout: 10_000 });
  }).toPass({ timeout: 180_000, intervals: [5_000] });
  await page.waitForTimeout(1500);
}

// One walk-through in a fixed order: later screens show the data the earlier steps create (tenant BANK1).
test('manual screenshots', async ({ browser }) => {
  test.setTimeout(900_000);
  const run = unique('').slice(-4);
  const stamp = Date.now().toString().slice(-7);
  const cifA = `C7${stamp}`;
  const cifB = `C8${stamp}`;
  const cifC = `C9${stamp}`;
  const titleA = `Passport Aisha Al Balushi ${run}`;
  const titleB = `Passport Salim Al Harthy ${run}`;
  const titleC = `Passport Khalid Al Kindi ${run}`;

  await step('sign-in', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    await page.addInitScript(() => window.localStorage.setItem('jdms.language', 'en'));
    await page.goto('/');
    await shot(page, '01-login');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.locator('#username').waitFor();
    await page.waitForLoadState('networkidle');
    await shot(page, '02-keycloak');
    await context.close();
  });

  const maker = await user(browser, 'maker1');
  const checker = await user(browser, 'checker1');
  let idA = '';
  let idC = '';

  await step('upload', async () => {
    await fillPassport(maker, cifA, titleA, 'Aisha Al Balushi');
    await shot(maker, '04-upload-form', true);
    idA = await upload(maker);
    await expect(maker.locator('.jdms-page-header')).toContainText('Pending approval');
    await shot(maker, '05-document-pending');
  });
  await step('upload B and C', async () => {
    await fillPassport(maker, cifB, titleB, 'Salim Al Harthy');
    await upload(maker);
    await fillPassport(maker, cifC, titleC, 'Khalid Al Kindi');
    idC = await upload(maker);
  });
  await step('home', async () => {
    await maker.goto('/');
    await expect(maker.getByRole('heading', { level: 1, name: /^Welcome/ })).toBeVisible();
    await expect(maker.locator('.jdms-home-recent')).toContainText(titleC);
    await shot(maker, '03-home');
  });
  await step('tasks', async () => {
    // opened from its address, so the page starts at the top with the task beside the list
    await openTask(checker, titleA);
    await checker.goto(checker.url());
    const detail = checker.getByRole('region', { name: 'Task', exact: true });
    await expect(detail.getByTestId('pdf-canvas')).toBeVisible({ timeout: 60_000 });
    await shot(checker, '10-tasks');
    await detail.getByRole('textbox', { name: 'Comment' }).fill('Checked against the original passport at the counter.');
    await detail.locator('.jdms-task-footer').scrollIntoViewIfNeeded();
    await shot(checker, '11-task-open');
    await checker.evaluate(() => window.scrollTo(0, 0));
    await detail.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(checker.getByText('Decision recorded')).toBeVisible();
    await expect(detail.getByRole('heading', { level: 2, name: new RegExp(titleB) })).toBeVisible();
    await expect(detail.getByTestId('pdf-canvas')).toBeVisible({ timeout: 60_000 });
    await shot(checker, '12-task-decision');
    await decide(checker, titleC, 'Approve');
  });
  await step('document views', async () => {
    await openWithPreview(maker, idA);
    await expect(maker.locator('.jdms-page-header')).toContainText('Active');
    await shot(maker, '06-document-overview');
    await maker.getByRole('tab', { name: /^Versions/ }).click();
    await shot(maker, '08-document-versions');
    await maker.getByRole('tab', { name: 'Approval history' }).click();
    await shot(maker, '09-document-history');
  });
  await step('new version', async () => {
    await maker.goto(`/documents/${idA}`);
    await maker.getByRole('button', { name: 'New version' }).click();
    const panel = maker.getByRole('region', { name: 'New version' });
    await panel.getByRole('textbox').fill('Renewed passport, new photo page');
    await panel.locator('input[type=file]').setInputFiles({ name: 'renewed.pdf', mimeType: 'application/pdf',
      buffer: pdf(`Passport data page - Aisha Al Balushi - ${cifA} - renewed`) });
    await shot(maker, '13-new-version');
  });
  await step('more actions', async () => {
    await maker.goto(`/documents/${idC}`);
    await maker.getByRole('button', { name: 'More actions' }).click();
    await expect(maker.getByRole('menuitem', { name: 'Delete…' })).toBeVisible();
    await shot(maker, '13b-more-actions');
  });
  await step('browse', async () => {
    await maker.goto('/browse/CUSTOMERS');
    await maker.getByRole('cell', { name: cifA, exact: true }).click();
    await expect(maker.getByRole('region', { name: 'Properties' })).toContainText(titleA);
    await shot(maker, '14-browse');
  });
  await step('search', async () => {
    await maker.goto('/search');
    await shot(maker, '15a-search-start');
    await maker.getByRole('searchbox', { name: 'Search text' }).fill('passport Al Balushi');
    await maker.getByRole('searchbox', { name: 'Search text' }).press('Enter');
    await expect(maker.getByRole('heading', { level: 1, name: /results? for/ })).toBeVisible();
    await shot(maker, '15-search-results');
    await maker.getByRole('button', { name: 'Preview' }).first().click();
    await expect(maker.getByTestId('pdf-canvas')).toBeVisible({ timeout: 60_000 });
    await maker.waitForTimeout(1500);
    await shot(maker, '15b-search-preview');
  });
  await step('delete', async () => {
    await maker.goto(`/documents/${idC}`);
    await maker.getByRole('button', { name: 'More actions' }).click();
    await maker.getByRole('menuitem', { name: 'Delete…' }).click();
    await expect(maker.getByRole('button', { name: 'Delete document' })).toBeVisible();
    await shot(maker, '16-delete-confirm');
    await maker.getByRole('button', { name: 'Delete document' }).click();
    await expect(maker.getByText('Deletion done or sent for approval')).toBeVisible();
    await decide(checker, titleC, 'Approve');
  });

  const records = await user(browser, 'records1');
  await step('recycle bin', async () => {
    await records.goto('/recycle-bin');
    await expect(records.getByRole('row', { name: new RegExp(titleC) })).toBeVisible();
    await shot(records, '17-recycle-bin');
  });
  await step('records', async () => {
    await records.goto('/records');
    await expect(records.getByText('Litigation - Aisha Al Balushi')).toBeVisible();
    // example holds of earlier runs are released first, so the list shows one of each
    const earlier = records.getByRole('listitem').filter({ hasText: 'Litigation - Salim Al Harthy' })
      .filter({ has: records.getByRole('button', { name: 'Release…' }) });
    while (await earlier.count()) {
      await earlier.first().getByRole('button', { name: 'Release…' }).click();
      await records.getByRole('dialog').getByRole('button', { name: 'Release hold' }).click();
      await expect(records.getByRole('dialog')).toHaveCount(0);
      await records.waitForTimeout(1000);
    }
    await records.reload();
    await expect(records.locator('#holds')).toBeVisible();
    await shot(records, '18-records');
    await records.getByRole('button', { name: 'New legal hold' }).click();
    const form = records.getByRole('region', { name: 'New legal hold' });
    await form.getByLabel('Name', { exact: true }).fill(`Litigation - Salim Al Harthy ${run}`);
    await form.getByLabel('Reason', { exact: true }).fill('Court case 2026/207: keep all customer documents');
    await form.getByLabel('Hold all documents of CIF').fill(cifB);
    await shot(records, '19a-records-new-hold');
    await form.getByRole('button', { name: 'Place hold' }).click();
    await expect(records.getByText('Legal hold placed')).toBeVisible();
    await records.evaluate(() => window.scrollTo(0, 0));
    await shot(records, '19-records-holds');
    const hold = records.getByRole('listitem').filter({ hasText: `Litigation - Salim Al Harthy ${run}` });
    await hold.getByRole('button', { name: 'Release…' }).click();
    await expect(records.getByRole('dialog', { name: 'Release this hold?' })).toBeVisible();
    await shot(records, '19b-records-release');
    // the example hold is released again, so runs do not pile up active holds
    await records.getByRole('dialog').getByRole('button', { name: 'Release hold' }).click();
    await expect(records.getByText('Legal hold released')).toBeVisible();
    await records.goto('/records#retention');
    await records.getByRole('button', { name: 'New policy' }).click();
    const policy = records.getByRole('region', { name: 'New policy' });
    await policy.getByLabel('Code', { exact: true }).fill('KYC_10Y');
    await policy.getByLabel('Name', { exact: true }).fill('Customer identity documents: 10 years after expiry');
    await choose(records, 'Starts from', 'Expiry date', policy);
    await policy.getByLabel('Years', { exact: true }).fill('10');
    await policy.scrollIntoViewIfNeeded();
    await shot(records, '19c-records-new-policy');
    await policy.getByRole('button', { name: 'Cancel' }).click();
    await records.goto('/records#disposition');
    await records.locator('#disposition').scrollIntoViewIfNeeded();
    await shot(records, '20-records-disposition');
  });

  const admin = await user(browser, 'tenantadmin1');
  await step('administration', async () => {
    await admin.goto('/admin/types');
    await expect(admin.getByText('PASSPORT', { exact: true })).toBeVisible();
    await shot(admin, '21-admin-types');
    await admin.getByText('PASSPORT', { exact: true }).click();
    await expect(admin.getByRole('region', { name: 'Document type details' })).toContainText('Passport number');
    await shot(admin, '21b-admin-type-detail');
    await admin.goto('/admin/types/new');
    const editor = admin.getByRole('region', { name: 'New document type' });
    await choose(admin, 'Cabinet', 'Customers');
    await editor.getByLabel('Code', { exact: true }).fill('RESIDENCE_PERMIT');
    await editor.getByLabel('Category', { exact: true }).fill('KYC');
    await editor.getByLabel('Name (English)').fill('Residence permit');
    await editor.getByLabel('Name (Arabic)').fill('تصريح الإقامة');
    await editor.getByRole('button', { name: 'Add field' }).click();
    const field = editor.getByRole('group', { name: 'Field 1' });
    await field.getByLabel('Key').fill('permit_no');
    await field.getByLabel('Label (English)').fill('Permit number');
    await field.getByLabel('Label (Arabic)').fill('رقم التصريح');
    await field.getByRole('switch').click();
    await shot(admin, '22-admin-new-type', true);
    await editor.getByRole('button', { name: 'Propose' }).click();
    await expect(admin.getByText('Proposed; another administrator must approve it')).toBeVisible();
  });
  const admin2 = await user(browser, 'tenantadmin2');
  await step('pending changes', async () => {
    await admin2.goto('/admin/changes');
    const row = admin2.getByRole('row', { name: /RESIDENCE_PERMIT/ });
    await expect(row).toBeVisible();
    await shot(admin2, '23-admin-pending');
    // the example is not kept: the second administrator rejects it
    await row.getByRole('button', { name: 'Reject' }).click();
    await admin2.getByRole('textbox', { name: 'Why is this change rejected?' }).fill('Example for the user manual');
    await admin2.locator('.ant-table-expanded-row').getByRole('button', { name: 'Reject' }).click();
    await expect(admin2.getByText('Decision recorded')).toBeVisible();
  });
  const security = await user(browser, 'secadmin1');
  await step('access rules and audit', async () => {
    await security.goto('/admin/access');
    await expect(security.getByRole('rowheader', { name: 'Maker' })).toBeVisible();
    await shot(security, '24-access-rules');
    await security.getByRole('button', { name: 'Checker, Approve: Allowed' }).click();
    await expect(security.getByRole('region', { name: 'Rule details' })).toBeVisible();
    await shot(security, '24b-access-rule-detail');
    await security.goto('/audit');
    await expect(security.locator('.ant-table-row').first()).toBeVisible();
    await security.getByRole('button', { name: 'Verify now' }).click();
    await expect(security.getByText('Chain intact')).toBeVisible({ timeout: 120_000 });
    await shot(security, '25-audit');
    await security.locator('.ant-table-row-expand-icon').first().click();
    await expect(security.locator('.ant-table-expanded-row').first()).toBeVisible();
    await shot(security, '25b-audit-event');
  });

  const ar = await user(browser, 'maker1', 'ar');
  await step('arabic', async () => {
    await ar.goto('/');
    await expect(ar.locator('html')).toHaveAttribute('dir', 'rtl');
    await shot(ar, '26-ar-home');
    await openWithPreview(ar, idA);
    await shot(ar, '27-ar-document');
    await ar.goto('/upload');
    const boxes = ar.getByRole('combobox');
    await boxes.nth(0).click();
    await ar.locator('.ant-select-dropdown:visible .ant-select-item-option').first().click();
    await ar.waitForTimeout(800);
    await boxes.nth(1).click();
    await boxes.nth(1).fill('جواز');
    await ar.waitForTimeout(800);
    await ar.locator('.ant-select-dropdown:visible .ant-select-item-option').first().click();
    await shot(ar, '28-ar-upload');
  });
  const viewer = await user(browser, 'viewer1');
  await step('viewer', async () => {
    await viewer.goto('/');
    await shot(viewer, '29-viewer-home');
  });
  const phone = await user(browser, 'checker1', 'en', 390);
  await step('phone', async () => {
    await phone.goto('/');
    await shot(phone, '30-phone-home');
    await phone.getByRole('button', { name: 'Menu' }).click();
    await phone.waitForTimeout(800);
    await shot(phone, '31-phone-menu');
    await phone.keyboard.press('Escape');
    await openTask(phone, titleB);
    await phone.goto(phone.url());
    await expect(phone.getByRole('region', { name: 'Task', exact: true }).getByTestId('pdf-canvas')).toBeVisible({ timeout: 60_000 });
    await shot(phone, '32-phone-task');
  });
  console.log('SHOTS ' + log.join(','));
});
