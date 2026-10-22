import { test } from '@cloud-notes-test/kit/playwright';
import {
  clickEdgelessModeButton,
  dblclickNoteBody,
  locateEditorContainer,
  locateToolbar,
} from '@cloud-notes-test/kit/utils/editor';
import { pressEnter } from '@cloud-notes-test/kit/utils/keyboard';
import { openHomePage } from '@cloud-notes-test/kit/utils/load-page';
import {
  clickNewPageButton,
  type,
  waitForEditorLoad,
} from '@cloud-notes-test/kit/utils/page-logic';
import { isContainedInBoundingBox } from '@cloud-notes-test/kit/utils/utils';
import { expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await openHomePage(page);
  await waitForEditorLoad(page);
  await clickNewPageButton(page);
  await clickEdgelessModeButton(page);
  const container = locateEditorContainer(page);
  await container.click();
});

test('should close embed editing modal when editor switching to page mode by short cut', async ({
  page,
}) => {
  await page.keyboard.press('@');
  await page.getByTestId('cmdk-label').getByText('Getting Started').click();
  const toolbar = locateToolbar(page);
  await toolbar.getByLabel('Switch view').click();
  await toolbar.getByLabel('Card view').click();
  await toolbar.getByLabel('Edit').click();

  const editingModal = page.locator('embed-card-edit-modal');
  await expect(editingModal).toBeVisible();

  await page.keyboard.press('Alt+s');
  await waitForEditorLoad(page);
  await expect(editingModal).toBeHidden();
});

test('embed card should not overflow the edgeless note', async ({ page }) => {
  const note = page.locator('cloud-notes-edgeless-note');
  await dblclickNoteBody(page);
  await type(page, '/github');
  await pressEnter(page);
  await page
    .locator('.embed-card-modal-input')
    .fill('https://github.com/diegonmarcos/cloud-u-android/pull/10442');
  await pressEnter(page);

  const embedCard = page.locator('cloud-notes-embed-github-block');
  await embedCard
    .locator('.cloud-notes-embed-github-block:not(.loading)')
    .waitFor({ state: 'visible' });
  expect(await isContainedInBoundingBox(note, embedCard, true)).toBe(true);
});
