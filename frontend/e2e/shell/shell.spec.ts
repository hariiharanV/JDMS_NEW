import { expect, test } from '@playwright/test';

test('sign-in screen in English, then Arabic right to left', async ({ page }, testInfo) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Welcome to JDMS Next' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');

  await page.getByText('العربية').click();

  await expect(page.getByRole('heading', { name: 'مرحبًا بك في JDMS Next' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'تسجيل الدخول' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
  await testInfo.attach('login-ar-rtl', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
});

test('the chosen language is remembered', async ({ page }) => {
  await page.goto('/');
  await page.getByText('العربية').click();
  await page.reload();

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
});

test('sign-in is reachable by keyboard with a visible focus ring', async ({ page }) => {
  await page.goto('/');
  const signIn = page.getByRole('button', { name: 'Sign in' });
  for (let i = 0; i < 6 && !(await signIn.evaluate((element) => element === document.activeElement)); i++) {
    await page.keyboard.press('Tab');
  }

  await expect(signIn).toBeFocused();
  const outline = await signIn.evaluate((element) => getComputedStyle(element).outlineStyle);
  expect(outline).not.toBe('none');
});
