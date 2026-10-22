import { test } from '@cloud-notes-test/kit/playwright';
import { openHomePage } from '@cloud-notes-test/kit/utils/load-page';
import {
  clickNewPageButton,
  waitForEditorLoad,
} from '@cloud-notes-test/kit/utils/page-logic';
import { expect } from '@playwright/test';

test('Click ai-land icon', async ({ page }) => {
  test.skip(process.env.CI !== undefined, 'Skip test in CI');
  await openHomePage(page);
  await waitForEditorLoad(page);
  await clickNewPageButton(page);
  await page.locator('[data-testid=ai-island]').click();

  await expect(page.getByTestId('chat-panel-input-container')).toBeVisible();
});
