import { Locator, Page, expect, test } from '@playwright/test';
import { byId, deleteAllCards } from './helpers';

/** CDK drag & drop needs real pointer moves (dragTo alone is too fast for it). */
async function drag(page: Page, from: Locator, to: Locator): Promise<void> {
  const a = await from.boundingBox();
  const b = await to.boundingBox();
  if (!a || !b) throw new Error('drag: element not visible');
  await page.mouse.move(a.x + 12, a.y + 12);
  await page.mouse.down();
  await page.mouse.move(a.x + 20, a.y + 20, { steps: 5 });
  await page.mouse.move(b.x + b.width / 2, b.y + 60, { steps: 20 });
  await page.mouse.move(b.x + b.width / 2, b.y + 70, { steps: 5 });
  await page.mouse.up();
}

async function addCard(page: Page, ticker: string, column: 'SUPPORT' | 'RESISTANCE', price: string): Promise<void> {
  await byId(page, 'kanban-add-ticker').fill(ticker);
  await byId(page, 'kanban-add-column').selectOption(column);
  await byId(page, 'kanban-add-price').fill(price);
  await byId(page, 'kanban-add-button').click();
}

test.describe('Portfolio kanban (support / resistance)', () => {
  test.beforeEach(async ({ request }) => {
    await deleteAllCards(request);
  });

  test('create, edit, move and delete a card', async ({ page, request }) => {
    await page.goto('/portfolio');
    const support = byId(page, 'kanban-column-SUPPORT');
    const resistance = byId(page, 'kanban-column-RESISTANCE');
    await expect(support).toBeVisible();
    await expect(resistance).toBeVisible();

    // far-away target so no alert fires during the test
    await addCard(page, 'AAPL', 'SUPPORT', '1');
    const card = support.locator('[data-testid^="kanban-card-"][data-armed]').first();
    await expect(card).toBeVisible();
    const id = (await card.getAttribute('data-testid'))!.replace('kanban-card-', '');
    await expect(byId(page, `kanban-card-live-${id}`)).toHaveText(/\d/); // live price label
    await expect(card).toHaveAttribute('data-armed', 'true');

    // edit the target price
    const priceInput = byId(page, `kanban-card-price-${id}`);
    await priceInput.fill('2.5');
    await priceInput.press('Enter');
    await expect.poll(async () => {
      const c = (await (await request.get('/api/kanban')).json()).find((x: { id: string }) => x.id === id);
      return c?.targetPrice;
    }).toBe(2.5);

    // invalid edit is rejected
    await priceInput.fill('-3');
    await priceInput.press('Enter');
    await expect(byId(page, `kanban-card-error-${id}`)).toBeVisible();
    await priceInput.press('Escape');

    // move SUPPORT -> RESISTANCE (target 2.5 is far below the price: RESISTANCE would fire; use a high target first)
    await request.patch(`/api/kanban/${id}`, { data: { targetPrice: 1000000 } });
    await page.reload();
    await drag(page, byId(page, `kanban-card-${id}`), resistance);
    await expect(resistance.getByTestId(`kanban-card-${id}`)).toBeVisible();
    await expect.poll(async () => {
      const c = (await (await request.get('/api/kanban')).json()).find((x: { id: string }) => x.id === id);
      return c?.column;
    }).toBe('RESISTANCE');

    // delete
    await byId(page, `kanban-card-delete-${id}`).click();
    await expect(byId(page, `kanban-card-${id}`)).toHaveCount(0);
    expect((await (await request.get('/api/kanban')).json()).length).toBe(0);
  });

  test('invalid card is rejected with an error', async ({ page }) => {
    await page.goto('/portfolio');
    await addCard(page, 'AAPL', 'SUPPORT', '0');
    await expect(byId(page, 'kanban-add-error')).toBeVisible();
  });

  test('creating a card and hitting its target pop up notifications', async ({ page }) => {
    await page.goto('/portfolio');
    // RESISTANCE at 1: the live price is already above it -> CARD_CREATED then TARGET_HIT
    await addCard(page, 'MSFT', 'RESISTANCE', '1');
    const toast = byId(page, 'toast');
    await expect(toast.first()).toBeVisible({ timeout: 20_000 });
    await expect(toast.filter({ hasText: 'MSFT' }).first()).toBeVisible();

    const card = byId(page, 'kanban-column-RESISTANCE').locator('[data-testid^="kanban-card-"][data-armed]').first();
    await expect(card).toHaveAttribute('data-armed', 'false', { timeout: 20_000 });

    await byId(page, 'toast-close').first().click();
  });
});
