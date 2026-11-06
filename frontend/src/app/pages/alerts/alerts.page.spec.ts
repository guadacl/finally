import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeEventSourceFactory } from '../../../testing/fake-event-source';
import { WatchlistItem } from '../../api/models';
import { EVENT_SOURCE_FACTORY } from '../../core/event-source';
import { PriceStreamService } from '../../core/price-stream.service';
import { ToastService } from '../../core/toast.service';
import { WatchlistService } from '../../core/watchlist.service';
import { AlertsPage, rsiZone } from './alerts.page';

const wl = (ticker: string): WatchlistItem => ({ ticker, addedAt: '2026-11-03T10:00:00Z' });

describe('AlertsPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(WatchlistService).items.set([{ ...wl('AAPL'), name: 'Apple Inc.' }, wl('MSFT')]);
  });

  afterEach(() => {
    // the page also (re)loads the Support / Resistance cards
    http.match((r) => r.method === 'GET' && r.url === '/api/kanban').forEach((r) => r.flush([]));
    http.verify();
  });

  const q = (root: HTMLElement, id: string) => root.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const type = (el: HTMLElement | null, value: string) => {
    const input = el as HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
  };

  it('opens the form with the + button, offers only watchlist stocks, and adds an alert', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([]);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    expect(q(root, 'alerts-empty')).not.toBeNull();
    expect(q(root, 'alerts-form')).toBeNull();

    q(root, 'alerts-add-button')!.click();
    await fixture.whenStable();
    const options = Array.from((q(root, 'alerts-ticker') as HTMLSelectElement).options).map((o) => o.value).filter(Boolean);
    expect(options).toEqual(['AAPL', 'MSFT']);
    const labels = Array.from((q(root, 'alerts-ticker') as HTMLSelectElement).options).filter((o) => o.value).map((o) => o.textContent!.trim());
    expect(labels).toEqual(['AAPL — Apple Inc.', 'MSFT']); // company name when known

    TestBed.inject(PriceStreamService).apply({ ticker: 'MSFT', price: 500, previousPrice: 499, timestamp: '2026-11-03T10:00:00Z', direction: 'up' });
    type(q(root, 'alerts-ticker'), 'MSFT');
    type(q(root, 'alerts-upper'), '600');
    type(q(root, 'alerts-lower'), '450');
    type(q(root, 'alerts-comment'), 'earnings week');
    await fixture.whenStable();
    (q(root, 'alerts-form') as HTMLFormElement).dispatchEvent(new Event('submit'));

    const post = http.expectOne({ method: 'POST', url: '/api/alerts' });
    expect(post.request.body).toEqual({ ticker: 'MSFT', upperLimit: 600, lowerLimit: 450, comment: 'earnings week' });
    post.flush({ id: 'x1', ticker: 'MSFT', upperLimit: 600, lowerLimit: 450, comment: 'earnings week', currentPrice: 500, createdAt: '2026-11-03T10:00:00Z' },
      { status: 201, statusText: 'Created' });
    await fixture.whenStable();

    // the lower limit becomes a Support card, the upper limit a Resistance card; both carry the comment
    const cardPosts = http.match({ method: 'POST', url: '/api/kanban' });
    expect(cardPosts.map((r) => r.request.body)).toEqual([
      { ticker: 'MSFT', column: 'SUPPORT', targetPrice: 450, comment: 'earnings week' },
      { ticker: 'MSFT', column: 'RESISTANCE', targetPrice: 600, comment: 'earnings week' },
    ]);
    cardPosts.forEach((r, i) =>
      r.flush({ id: 'k' + i, ...r.request.body, sortOrder: i, armed: true, createdAt: '2026-11-03T10:00:00Z', updatedAt: '2026-11-03T10:00:00Z' },
        { status: 201, statusText: 'Created' }));
    await fixture.whenStable();
    expect(q(root, 'target-table-SUPPORT')!.textContent).toContain('MSFT');
    expect(q(root, 'target-table-RESISTANCE')!.textContent).toContain('MSFT');

    expect(q(root, 'alerts-form')).toBeNull();
    const row = q(root, 'alerts-row-x1')!;
    expect(row.textContent).toContain('MSFT');
    expect(row.textContent).not.toContain('earnings week'); // the comment lives on the cards now
    expect((q(root, 'target-comment-k0') as HTMLInputElement).value).toBe('earnings week');
    expect(row.getAttribute('data-status')).toBe('inside');
  });

  it('creates only the card for the limit that was set', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([]);
    const root: HTMLElement = fixture.nativeElement;
    q(root, 'alerts-add-button')!.click();
    await fixture.whenStable();
    type(q(root, 'alerts-ticker'), 'AAPL');
    type(q(root, 'alerts-upper'), '400');
    await fixture.whenStable();
    (q(root, 'alerts-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/alerts' })
      .flush({ id: 'u1', ticker: 'AAPL', upperLimit: 400, createdAt: '2026-11-03T10:00:00Z' }, { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    const card = http.expectOne({ method: 'POST', url: '/api/kanban' });
    expect(card.request.body).toEqual({ ticker: 'AAPL', column: 'RESISTANCE', targetPrice: 400 });
    card.flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    // the alert stays; the failed card is reported as a toast
    expect(q(root, 'alerts-row-u1')).not.toBeNull();
    expect(TestBed.inject(ToastService).toasts().at(-1)?.message).toContain('Resistance card could not be created');
  });

  it('shows RSI(14) coloured by zone, and a dash when unknown', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    const base = { lowerLimit: 1, createdAt: '2026-11-03T10:00:00Z' };
    http.expectOne('/api/alerts').flush([
      { ...base, id: 'r1', ticker: 'AAPL', rsi: 74.25 },
      { ...base, id: 'r2', ticker: 'MSFT', rsi: 28 },
      { ...base, id: 'r3', ticker: 'NVDA', rsi: 50 },
      { ...base, id: 'r4', ticker: 'TSLA' },
    ]);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    const cell = (id: string) => q(root, 'alerts-rsi-' + id)!;
    expect(cell('r1').textContent!.trim()).toBe('74.3');
    expect(cell('r1').className).toContain('text-down');
    expect(cell('r2').textContent!.trim()).toBe('28.0');
    expect(cell('r2').className).toContain('text-up');
    expect(cell('r3').className).toContain('text-muted');
    expect(cell('r4').textContent!.trim()).toBe('—');
    expect(cell('r4').getAttribute('title')).toContain('Massive');
    expect([rsiZone(70), rsiZone(30), rsiZone(30.1), rsiZone(null)]).toEqual(['overbought', 'oversold', 'neutral', 'unknown']);
  });

  it('shows validation errors without calling the API', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([]);
    const root: HTMLElement = fixture.nativeElement;
    q(root, 'alerts-add-button')!.click();
    await fixture.whenStable();
    type(q(root, 'alerts-ticker'), 'AAPL');
    await fixture.whenStable();
    (q(root, 'alerts-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(q(root, 'alerts-error')!.textContent).toContain('upper limit, a lower limit');
  });

  it('shows day low/high (widened by the live price) and last-session volume', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([{ id: 'v1', ticker: 'AAPL', name: 'Apple Inc.', upperLimit: 400, dayHigh: 343.8, dayLow: 335.82,
      lastSessionVolume: 64_757_691, lastSessionDate: '2026-11-02', createdAt: '2026-11-03T10:00:00Z' }]);
    TestBed.inject(PriceStreamService).apply({ ticker: 'AAPL', price: 345.1, previousPrice: 344, timestamp: '2026-11-03T15:00:00Z', direction: 'up' });
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    expect(q(root, 'alerts-daylow-v1')!.textContent).toContain('335.82');
    expect(q(root, 'alerts-dayhigh-v1')!.textContent).toContain('345.10');
    expect(q(root, 'alerts-volume-v1')!.textContent).toContain('64.76M');
    expect(q(root, 'alerts-volume-v1')!.getAttribute('title')).toContain('2 Nov');
    expect(q(root, 'alerts-name-v1')!.textContent!.trim()).toBe('Apple Inc.');
  });

  it('filters the price alerts from the funnel icon in the table corner', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([
      { id: 'f1', ticker: 'AAPL', name: 'Apple Inc.', lowerLimit: 100, createdAt: '2026-11-03T10:00:00Z' },
      { id: 'f2', ticker: 'MSFT', name: 'Microsoft Corporation', upperLimit: 600, createdAt: '2026-11-03T10:00:00Z' },
    ]);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    const rows = () => Array.from(root.querySelectorAll('tr[data-testid^="alerts-row-"]')).map((r) => r.getAttribute('data-testid'));
    expect(q(root, 'alerts-search')).toBeNull(); // no search field any more
    q(root, 'alerts-filter-toggle')!.click();
    await fixture.whenStable();
    type(q(root, 'alerts-filter'), 'micro');
    await fixture.whenStable();
    expect(rows()).toEqual(['alerts-row-f2']);
    expect(q(root, 'alerts-filter-toggle')!.className).toContain('text-accent');
  });

  it('deletes an alert', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([{ id: 'd1', ticker: 'AAPL', lowerLimit: 100, createdAt: '2026-11-03T10:00:00Z' }]);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    q(root, 'alerts-delete-d1')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/alerts/d1' }).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(q(root, 'alerts-row-d1')).toBeNull();
  });
});
