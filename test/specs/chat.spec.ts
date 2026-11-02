import { expect, test } from '@playwright/test';
import { byId, openChat, removeFromWatchlist, sellAll } from './helpers';

// Requires the app to run with LLM_MOCK=true (deterministic responses, no network).
test.describe('AI chat (mocked LLM)', () => {
  test.beforeEach(async ({ request }) => {
    await sellAll(request);
    await removeFromWatchlist(request, 'PYPL');
  });

  test('send a message, receive a response with an executed trade inline', async ({ page, request }) => {
    await page.goto('/markets');
    await openChat(page);
    await byId(page, 'chat-input').fill('buy 2 AAPL');
    await byId(page, 'chat-send').click();

    const assistant = byId(page, 'chat-message').filter({ has: page.locator('[data-testid="chat-action"]') }).last();
    await expect(assistant).toBeVisible({ timeout: 15_000 });
    const action = assistant.getByTestId('chat-action').first();
    await expect(action).toHaveAttribute('data-status', 'executed');
    await expect(action).toContainText('AAPL');

    const pf = await (await request.get('/api/portfolio')).json();
    expect(pf.positions.find((p: { ticker: string }) => p.ticker === 'AAPL')?.quantity).toBe(2);
  });

  test('watchlist change via chat and failed action reporting', async ({ page }) => {
    await page.goto('/markets');
    await openChat(page);
    await byId(page, 'chat-input').fill('add PYPL to my watchlist');
    await byId(page, 'chat-send').click();
    await expect(byId(page, 'watchlist-row-PYPL')).toBeVisible({ timeout: 15_000 });

    await byId(page, 'chat-input').fill('sell 500 TSLA');
    await byId(page, 'chat-send').click();
    await expect(page.locator('[data-testid="chat-action"][data-status="failed"]').last()).toBeVisible({ timeout: 15_000 });
  });

  test('conversation history survives a reload', async ({ page }) => {
    await page.goto('/markets');
    await openChat(page);
    const marker = `how is my portfolio ${Date.now()}`;
    await byId(page, 'chat-input').fill(marker);
    await byId(page, 'chat-send').click();
    await expect(byId(page, 'chat-message').filter({ hasText: marker })).toBeVisible();
    await page.reload();
    await openChat(page);
    await expect(byId(page, 'chat-message').filter({ hasText: marker })).toBeVisible();
  });
});
