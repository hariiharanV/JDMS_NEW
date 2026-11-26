import { test, expect, type Page } from '@playwright/test';
import { signIn } from '../stack/support';

// Screenshots for the workflow setup guide (docs/manuals/build/workflow.js). Read-only: forms are opened and
// filled for the picture, then cancelled, so nothing is proposed. Safe against a shared test server.
//   SHOTS=../docs/manuals/screenshots npx playwright test -c playwright.manual.config.ts workflow-guide
const OUT = process.env.SHOTS ?? 'shots';

async function shot(page: Page, name: string) {
  await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, { timeout: 30_000 });
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${OUT}/${name}.png` });
}

test('approval route of a document type', async ({ page }) => {
  await signIn(page, 'tenantadmin1');
  await page.getByRole('link', { name: 'Document types' }).click();
  await page.getByText('New document type', { exact: true }).click();
  await page.getByRole('combobox', { name: 'Approval' }).click();
  await expect(page.getByTitle('Committee')).toBeVisible();
  await shot(page, '40-type-approval-options');
});

test('an approval rule for one user and one branch', async ({ page }) => {
  await signIn(page, 'secadmin1');
  await page.getByRole('link', { name: 'Access rules' }).click();
  await page.getByRole('button', { name: 'Propose a rule' }).click();
  const panel = page.getByRole('region', { name: 'Propose a rule' });
  await panel.getByRole('radio', { name: 'A user' }).check();
  await panel.getByLabel('User ID').fill('3f6c1d2e-8a4b-4c1f-9d2e-7b5a1c0e9f42');
  for (const action of ['View details', 'View file', 'Approve']) {
    await panel.getByRole('checkbox', { name: action, exact: true }).check();
  }
  await panel.getByRole('combobox', { name: 'Applies to' }).click();
  await page.getByTitle('Document type', { exact: true }).click();
  await panel.getByRole('combobox', { name: 'Document type' }).click();
  await page.getByTitle('Loan agreement').click();
  await panel.getByRole('combobox', { name: 'Branches' }).click();
  await page.getByTitle('These branches').click();
  await panel.getByLabel('Branch codes').fill('002');
  await panel.getByLabel('Description').fill('checker1 also approves loan agreements of branch 002');
  await panel.scrollIntoViewIfNeeded();
  await shot(page, '41-access-rule-user');
  await panel.getByRole('button', { name: 'Cancel' }).click();
});

test('committees screen and a new committee', async ({ page }) => {
  await signIn(page, 'tenantadmin1');
  await page.getByRole('link', { name: 'Committees' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Committees' })).toBeVisible();
  await page.getByRole('button', { name: 'New committee' }).click();
  await page.getByRole('textbox', { name: 'Name' }).fill('Credit committee');
  const members = [['Member 1', 'committee1'], ['Member 2', 'committee2'], ['Member 3', 'committee3']];
  for (const [group, username] of members) {
    const row = page.getByRole('group', { name: group });
    await row.getByRole('combobox', { name: 'User' }).click();
    // each row opens its own list; the newest is the last in the page
    await page.getByTitle(new RegExp(`\\(${username}\\)$`)).last().click();
  }
  await page.getByRole('group', { name: 'Member 1' }).getByRole('textbox', { name: 'Designation' }).fill('Chair');
  await shot(page, '43-committee-new');
  await page.getByRole('button', { name: 'Cancel' }).click();
});

test('users screen and a change of branches', async ({ page }) => {
  await signIn(page, 'secadmin1');
  await page.getByRole('link', { name: 'Users', exact: true }).click();
  await page.getByRole('link', { name: /checker/i }).first().click();
  await page.getByRole('button', { name: 'Change branches or clearance' }).click();
  await shot(page, '45-user-change-access');
  await page.getByRole('button', { name: 'Cancel' }).click();
});
