import { resolve } from 'node:path';

import { test, testResultDir } from '@cloud-notes-test/kit/playwright';
import { openHomePage } from '@cloud-notes-test/kit/utils/load-page';
import { waitForEditorLoad } from '@cloud-notes-test/kit/utils/page-logic';
import { expect } from '@playwright/test';

test.use({
  colorScheme: 'light',
});

// default could be anything, according to the system
test('default white', async ({ page }) => {
  await openHomePage(page);
  await waitForEditorLoad(page);
  const root = page.locator('html');
  const themeMode = await root.evaluate(element => element.dataset.theme);
  expect(themeMode).toBe('light');
  await page.screenshot({
    path: resolve(testResultDir, 'cloud-notes-light-theme.png'),
  });
  await page.getByTestId('settings-modal-trigger').click();
  await page.getByTestId('appearance-panel-trigger').click();
  await page.waitForTimeout(50);
  await page.getByTestId('dark-theme-trigger').click();
  const darkMode = await root.evaluate(element => element.dataset.theme);
  expect(darkMode).toBe('dark');
  await page.screenshot({
    path: resolve(testResultDir, 'cloud-notes-dark-theme.png'),
  });
});
