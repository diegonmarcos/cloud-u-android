import { test } from '@cloud-notes-test/kit/playwright';
import { createRandomUser, loginUser } from '@cloud-notes-test/kit/utils/cloud';
import { waitForEditorLoad } from '@cloud-notes-test/kit/utils/page-logic';

test.beforeEach(async ({ page }) => {
  const user = await createRandomUser();
  await loginUser(page, user);
});

test('import from template should work', async ({ page }) => {
  await page.goto('https://notes.diegonmarcos.com/templates', { waitUntil: 'load' });

  await page.click('.template-list > a:first-child');
  const importButton = page.getByRole('button', {
    name: 'Use this template',
  });

  const href = await importButton.evaluate((el: HTMLElement) => {
    const a = el.closest('a');
    if (!a) {
      throw new Error('Import link not found');
    }
    return a.href;
  });

  const url = new URL(href);

  await page.goto(url.pathname + url.search);

  const btn = page.getByTestId('import-template-to-workspace-btn');

  await btn.isVisible();
  await btn.click();
  await waitForEditorLoad(page);
});
