import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { signIn } from './support';

/** WCAG 2.2 A and AA, as the handoff's checklist asks (§5): no serious or critical violation on any screen. */
async function expectNoSeriousViolations(page: Page, label: string): Promise<void> {
  await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, { timeout: 20_000 });
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const serious = result.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => `${label}: ${violation.id} (${violation.nodes.map((node) => node.target.join(' ')).join(', ')})`);
  expect(serious).toEqual([]);
}

const SCREENS: { user: string; paths: string[] }[] = [
  { user: 'maker1', paths: ['/', '/tasks', '/upload', '/browse', '/search?q=passport', 'document'] },
  { user: 'checker1', paths: ['task'] },
  { user: 'records1', paths: ['/records', '/recycle-bin'] },
  { user: 'tenantadmin1', paths: ['/admin/types', '/admin/types/new', '/admin/changes'] },
  { user: 'secadmin1', paths: ['/admin/access', '/audit'] },
];

async function open(page: Page, path: string): Promise<void> {
  if (path === 'document') {
    await page.goto('/');
    await page.locator('.jdms-home-recent tbody a').first().click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  } else if (path === 'task') {
    await page.goto('/tasks');
    await page.locator('a.jdms-task-link').first().click();
    await expect(page.getByRole('heading', { level: 2 }).first()).toBeVisible();
  } else {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  }
}

for (const { language, width } of [{ language: 'en', width: 1440 }, { language: 'ar', width: 1440 }, { language: 'en', width: 390 }] as const) {
  test(`axe finds no serious violations: ${language} at ${width} px`, async ({ browser }) => {
    test.setTimeout(300_000);
    for (const { user, paths } of SCREENS) {
      const context = await browser.newContext({ viewport: { width, height: 900 } });
      const page = await context.newPage();
      await signIn(page, user, language);
      for (const path of paths) {
        await open(page, path);
        await expectNoSeriousViolations(page, `${user} ${path}`);
        // the page never scrolls sideways; wide tables scroll inside their card (handoff §1.7)
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${user} ${path} scrolls sideways`).toBeLessThanOrEqual(0);
      }
      await context.close();
    }
  });
}

test('reduced motion: nothing animates when the user asks for less motion', async ({ browser }) => {
  const context = await browser.newContext({ reducedMotion: 'reduce' });
  const page = await context.newPage();
  await signIn(page, 'maker1');
  const durations = await page.evaluate(() => [...document.querySelectorAll('button, a, .ant-btn, .jdms-stat')]
    .map((element) => getComputedStyle(element))
    .flatMap((style) => [style.transitionDuration, style.animationDuration])
    .flatMap((value) => value.split(',').map((part) => part.trim())));
  // index.css shortens every transition and animation to 0.01 ms (Chrome reports 1e-05s); antd's motion is off
  const seconds = (value: string) => (value.endsWith('ms') ? parseFloat(value) / 1000 : parseFloat(value));
  expect(durations.length).toBeGreaterThan(0);
  expect(durations.filter((value) => seconds(value) > 0.00001)).toEqual([]);
  await context.close();
});
