import { APIRequestContext, Locator, Page, expect } from '@playwright/test';

export const DEFAULT_TICKERS = ['AAPL', 'GOOGL', 'MSFT', 'AMZN', 'TSLA', 'NVDA', 'META', 'JPM', 'V', 'NFLX'];

export const byId = (page: Page, id: string): Locator => page.getByTestId(id);

/** Parses "$10,000.00" / "10,000.00" / "-1.5%" into a number. */
export function num(text: string | null): number {
  return Number((text ?? '').replace(/[^0-9.\-]/g, ''));
}

export async function waitForPrices(page: Page): Promise<void> {
  await expect(byId(page, 'connection-status')).toHaveAttribute('data-status', 'connected', { timeout: 20_000 });
  await expect(byId(page, 'watchlist-row-AAPL').getByTestId('watchlist-price')).toHaveText(/\d/);
}

/** Opens the AI chat panel if it is collapsed. */
export async function openChat(page: Page): Promise<void> {
  const input = byId(page, 'chat-input');
  if (!(await input.isVisible())) {
    await byId(page, 'chat-toggle').first().click();
  }
  await expect(input).toBeVisible();
}

// ---- API helpers used to set up / clean up state (same origin as the UI) ----

export async function sellAll(api: APIRequestContext): Promise<void> {
  const res = await api.get('/api/portfolio');
  const pf = await res.json();
  for (const p of pf.positions ?? []) {
    await api.post('/api/portfolio/trade', { data: { ticker: p.ticker, quantity: p.quantity, side: 'sell' } });
  }
}

export async function deleteAllCards(api: APIRequestContext): Promise<void> {
  const cards = await (await api.get('/api/kanban')).json();
  for (const c of cards) {
    await api.delete(`/api/kanban/${c.id}`);
  }
}

export async function removeFromWatchlist(api: APIRequestContext, ticker: string): Promise<void> {
  await api.delete(`/api/watchlist/${ticker}`); // 404 is fine
}

export async function cash(api: APIRequestContext): Promise<number> {
  return (await (await api.get('/api/portfolio')).json()).cashBalance;
}
