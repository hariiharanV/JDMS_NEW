import { expect, type Locator, type Page } from '@playwright/test';

/** Password of the jdms-local dev users (deploy/.env); never hard-coded. */
function devPassword(): string {
  const password = process.env.JDMS_DEV_USER_PASSWORD;
  if (!password) {
    throw new Error('Set JDMS_DEV_USER_PASSWORD (set -a; . deploy/.env; set +a) before npm run e2e:stack');
  }
  return password;
}

/** Signs in through Keycloak (authorization code + PKCE) and waits for the app. */
export async function signIn(page: Page, username: string, language: 'en' | 'ar' = 'en'): Promise<void> {
  await page.addInitScript((lang) => window.localStorage.setItem('jdms.language', lang), language);
  await page.goto('/');
  await page.getByRole('button', { name: language === 'ar' ? 'تسجيل الدخول' : 'Sign in' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(devPassword());
  await page.locator('#kc-login').click();
  await expect(page.getByRole('navigation')).toBeVisible({ timeout: 30_000 });
}

/** A unique value per run, so runs against the same local database never collide. */
export function unique(prefix: string): string {
  return `${prefix}${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1000)}`;
}

/** A small valid one-page PDF with the given text (no binary fixtures in the repository). */
export function pdf(text: string): Buffer {
  const stream = `BT /F1 24 Tf 72 720 Td (${text.replace(/[()\\]/g, '')}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let body = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((object, index) => {
    offsets.push(body.length);
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  body += offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, 'latin1');
}

/** Types into a searchable Ant Design select and takes the first match (long lists are virtual); checks it took. */
export async function search(page: Page, label: string | RegExp, text: string): Promise<void> {
  const box = page.getByRole('combobox', { name: label });
  const select = box.locator('xpath=ancestor::div[contains(concat(" ", @class, " "), " ant-select ")][1]');
  await expect(async () => {
    await box.click();
    await box.fill(text);
    const listId = await box.getAttribute('aria-controls');
    const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${listId}"]`) });
    await dropdown.locator('.ant-select-item-option').filter({ hasText: text }).first().click({ timeout: 5_000 });
    await expect(select).toContainText(text, { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

/**
 * Picks an option of an Ant Design select by its visible label. The option is looked up in the dropdown that
 * belongs to this select (the listbox named by its aria-controls), never in whichever dropdown happens to be visible.
 */
export async function choose(page: Page, label: string | RegExp, option: string | RegExp,
  within: Page | Locator = page): Promise<void> {
  const box = within.getByRole('combobox', { name: label });
  await box.click();
  await expect(box).toHaveAttribute('aria-expanded', 'true');
  const listId = await box.getAttribute('aria-controls');
  const dropdown = page.locator('.ant-select-dropdown').filter({ has: page.locator(`[id="${listId}"]`) });
  await dropdown.locator('.ant-select-item-option').filter({ hasText: option }).first().click();
  await expect(box).toHaveAttribute('aria-expanded', 'false');
}

/** Types a date into an Ant Design date picker (YYYY-MM-DD) and confirms it. */
export async function typeDate(page: Page, label: string | RegExp, value: string): Promise<void> {
  const input = page.getByLabel(label, { exact: true });
  await input.click();
  await input.fill(value);
  await input.press('Enter');
}

/** Uploads a PASSPORT for the CIF through the upload screen and returns the new document's ID. */
export async function uploadPassport(page: Page, cif: string, title: string): Promise<string> {
  await page.goto('/upload');
  await choose(page, 'Cabinet', 'Customers');
  await search(page, 'Document type', 'Passport');
  await page.getByLabel('Title', { exact: true }).fill(title);
  await page.getByLabel('CIF', { exact: true }).fill(cif);
  await typeDate(page, 'Expiry date', '2031-05-31');
  await page.getByLabel('Passport number', { exact: true }).fill(`P${cif}`);
  await search(page, 'Nationality', 'Oman');
  await typeDate(page, 'Issue date', '2021-06-01');
  await page.locator('input[type=file]').setInputFiles({ name: `${title}.pdf`, mimeType: 'application/pdf',
    buffer: pdf(`JDMS e2e ${title}`) });
  await page.getByRole('button', { name: 'Upload', exact: true }).click();
  await page.waitForURL(/\/documents\/[0-9a-f-]{36}$/);
  return page.url().split('/').pop() ?? '';
}

/** Opens the inbox task of the item with this title in the split view and returns the task pane. */
export async function openTask(page: Page, title: string): Promise<Locator> {
  await page.goto('/tasks');
  const list = page.getByRole('region', { name: 'Task list' });
  await expect(list.getByRole('link').first()).toBeVisible();
  // the inbox loads the largest page the API allows; older inboxes need "Show more"
  const task = list.getByRole('link', { name: new RegExp(title) }).first();
  for (let more = 0; more < 10 && !(await task.isVisible()); more++) {
    const button = list.getByRole('button', { name: /^Show \d+ more/ });
    if (!(await button.count())) break;
    await button.click();
    await expect(button.locator('.ant-btn-loading-icon')).toHaveCount(0);
  }
  await task.click();
  const detail = page.getByRole('region', { name: 'Task', exact: true });
  await expect(detail.getByRole('heading', { level: 2, name: new RegExp(title) })).toBeVisible();
  return detail;
}

/** Decides the inbox task of the item with this title, in place: no dialog. */
export async function decide(page: Page, title: string, decision: 'Approve' | 'Reject' | 'Return for changes',
  comment?: string): Promise<void> {
  const detail = await openTask(page, title);
  if (comment) {
    await detail.getByRole('textbox', { name: 'Comment' }).fill(comment);
  }
  await detail.getByRole('button', { name: decision, exact: true }).click();
  await expect(page.getByText('Decision recorded')).toBeVisible();
}
