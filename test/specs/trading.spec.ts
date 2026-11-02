import { expect, test } from '@playwright/test';
import { byId, cash, num, sellAll, waitForPrices } from './helpers';

test.describe('Trading & portfolio', () => {
  test.beforeEach(async ({ request }) => {
    await sellAll(request);
  });

  test('buy shares: cash decreases, position appears', async ({ page, request }) => {
    const before = await cash(request);
    await page.goto('/markets');
    await waitForPrices(page);

    await byId(page, 'trade-ticker').fill('AAPL');
    await byId(page, 'trade-quantity').fill('2');
    await byId(page, 'trade-buy').click();
    await expect(byId(page, 'trade-success')).toBeVisible();

    await expect.poll(async () => cash(request)).toBeLessThan(before);
    await expect.poll(async () => num(await byId(page, 'header-cash').textContent())).toBeLessThan(before);

    await page.goto('/portfolio');
    const row = byId(page, 'positions-row-AAPL');
    await expect(row).toBeVisible();
    await expect(row).toContainText('2');
    await expect(byId(page, 'heatmap-cell-AAPL')).toBeVisible();
  });

  test('sell shares: cash increases, position disappears', async ({ page, request }) => {
    await request.post('/api/portfolio/trade', { data: { ticker: 'MSFT', quantity: 3, side: 'buy' } });
    const before = await cash(request);
    await page.goto('/markets');
    await waitForPrices(page);

    await byId(page, 'trade-ticker').fill('MSFT');
    await byId(page, 'trade-quantity').fill('1');
    await byId(page, 'trade-sell').click();
    await expect(byId(page, 'trade-success')).toBeVisible();
    await expect.poll(async () => cash(request)).toBeGreaterThan(before);

    await byId(page, 'trade-quantity').fill('2');
    await byId(page, 'trade-sell').click();
    await expect.poll(async () => (await (await request.get('/api/portfolio')).json()).positions.length).toBe(0);

    await page.goto('/portfolio');
    await expect(byId(page, 'positions-row-MSFT')).toHaveCount(0);
  });

  test('rejected trades show an error', async ({ page }) => {
    await page.goto('/markets');
    await waitForPrices(page);
    await byId(page, 'trade-ticker').fill('NVDA');
    await byId(page, 'trade-quantity').fill('100000');
    await byId(page, 'trade-buy').click();
    await expect(byId(page, 'trade-error')).toBeVisible();

    await byId(page, 'trade-ticker').fill('TSLA');
    await byId(page, 'trade-quantity').fill('5');
    await byId(page, 'trade-sell').click();
    await expect(byId(page, 'trade-error')).toBeVisible();
  });

  test('portfolio visualizations: heatmap colors and P&L chart', async ({ page, request }) => {
    await request.post('/api/portfolio/trade', { data: { ticker: 'AAPL', quantity: 1, side: 'buy' } });
    await request.post('/api/portfolio/trade', { data: { ticker: 'JPM', quantity: 1, side: 'buy' } });
    await page.goto('/portfolio');
    await expect(byId(page, 'heatmap-cell-AAPL')).toBeVisible();
    await expect(byId(page, 'heatmap-cell-JPM')).toBeVisible();
    // each cell is colored (green/red/neutral) via an inline or computed background
    const bg = await byId(page, 'heatmap-cell-AAPL').evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(bg).not.toBe('rgba(0, 0, 0, 0)');

    await expect(byId(page, 'pnl-chart')).toBeVisible();
    const history = await (await request.get('/api/portfolio/history')).json();
    expect(history.length).toBeGreaterThan(0); // a snapshot is recorded after every trade
  });
});
