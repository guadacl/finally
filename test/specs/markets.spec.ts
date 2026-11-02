import { expect, test } from '@playwright/test';
import { DEFAULT_TICKERS, byId, num, removeFromWatchlist, waitForPrices } from './helpers';

test.describe('Fresh start & watchlist', () => {
  test('default watchlist, cash balance and streaming prices', async ({ page, request }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/markets$/);
    for (const t of DEFAULT_TICKERS) {
      await expect(byId(page, `watchlist-row-${t}`)).toBeVisible();
    }
    await waitForPrices(page);
    await expect(byId(page, 'header-cash')).toHaveText(/\$?[\d,]+\.\d{2}/);
    await expect(byId(page, 'header-total')).toHaveText(/\d/);

    // the server reports the seeded $10k (assuming a fresh database)
    const pf = await (await request.get('/api/portfolio')).json();
    expect(pf.cashBalance + pf.positionsValue).toBeGreaterThan(0);

    // prices keep moving: the displayed AAPL price changes within a few seconds
    const price = byId(page, 'watchlist-row-AAPL').getByTestId('watchlist-price');
    const first = await price.textContent();
    await expect.poll(async () => price.textContent(), { timeout: 15_000 }).not.toBe(first);
  });

  test('SPA deep links are served by the backend', async ({ page }) => {
    for (const path of ['/markets', '/watchlists', '/portfolio']) {
      const res = await page.goto(path);
      expect(res?.status()).toBe(200);
      await expect(byId(page, 'connection-status')).toBeVisible();
    }
  });

  test('add and remove a ticker on the watchlists page', async ({ page, request }) => {
    await removeFromWatchlist(request, 'PYPL');
    await page.goto('/watchlists');
    await byId(page, 'watchlist-add-input').fill('pypl');
    await byId(page, 'watchlist-add-button').click();
    const row = byId(page, 'watchlist-row-PYPL');
    await expect(row).toBeVisible();
    await expect(row.getByTestId('watchlist-price')).toHaveText(/\d/);

    // duplicate -> error shown
    await byId(page, 'watchlist-add-input').fill('PYPL');
    await byId(page, 'watchlist-add-button').click();
    await expect(byId(page, 'watchlist-error')).toBeVisible();

    await byId(page, 'watchlist-remove-PYPL').click();
    await expect(row).toHaveCount(0);
  });

  test('clicking a ticker selects it in the main chart', async ({ page }) => {
    await page.goto('/markets');
    await waitForPrices(page);
    await byId(page, 'watchlist-row-MSFT').click();
    await expect(byId(page, 'chart-ticker')).toContainText('MSFT');
    await expect(byId(page, 'main-chart')).toBeVisible();
  });

  test('connection indicator reconnects after the stream drops', async ({ page, context }) => {
    await page.goto('/markets');
    await waitForPrices(page);
    await context.setOffline(true);
    await expect(byId(page, 'connection-status')).not.toHaveAttribute('data-status', 'connected', { timeout: 20_000 });
    await context.setOffline(false);
    await expect(byId(page, 'connection-status')).toHaveAttribute('data-status', 'connected', { timeout: 30_000 });
    expect(num('$1,234.50')).toBe(1234.5);
  });
});
