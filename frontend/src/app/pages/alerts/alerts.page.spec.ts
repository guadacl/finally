import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeEventSourceFactory } from '../../../testing/fake-event-source';
import { WatchlistItem } from '../../api/models';
import { EVENT_SOURCE_FACTORY } from '../../core/event-source';
import { PriceStreamService } from '../../core/price-stream.service';
import { WatchlistService } from '../../core/watchlist.service';
import { AlertsPage } from './alerts.page';

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
    TestBed.inject(WatchlistService).items.set([wl('AAPL'), wl('MSFT')]);
  });

  afterEach(() => http.verify());

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

    expect(q(root, 'alerts-form')).toBeNull();
    // both limits → the alert shows in the support and in the resistance table
    for (const side of ['support', 'resistance']) {
      const row = q(root, `alerts-${side}-row-x1`)!;
      expect(row.textContent).toContain('MSFT');
      expect(row.textContent).toContain('earnings week');
      expect(row.getAttribute('data-status')).toBe('inside');
    }
    expect(q(root, 'alerts-support-margin-x1')!.textContent).toContain('+10.00%'); // (500 − 450) / 500
    expect(q(root, 'alerts-resistance-margin-x1')!.textContent).toContain('+20.00%'); // (600 − 500) / 500
  });

  it('splits alerts into a support and a resistance table, each showing only its own level', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([
      { id: 's1', ticker: 'AAPL', lowerLimit: 220, createdAt: '2026-11-03T10:00:00Z' },
      { id: 'r1', ticker: 'MSFT', upperLimit: 480, createdAt: '2026-11-03T10:00:00Z' },
    ]);
    const prices = TestBed.inject(PriceStreamService);
    prices.apply({ ticker: 'AAPL', price: 200, previousPrice: 201, timestamp: '2026-11-03T15:00:00Z', direction: 'down' });
    prices.apply({ ticker: 'MSFT', price: 400, previousPrice: 399, timestamp: '2026-11-03T15:00:00Z', direction: 'up' });
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;

    const support = q(root, 'alerts-table-support')!;
    const resistance = q(root, 'alerts-table-resistance')!;
    expect(q(support, 'alerts-support-row-s1')).not.toBeNull();
    expect(q(support, 'alerts-support-row-r1')).toBeNull();
    expect(q(resistance, 'alerts-resistance-row-r1')).not.toBeNull();
    expect(q(resistance, 'alerts-resistance-row-s1')).toBeNull();

    const headers = (t: HTMLElement) => Array.from(t.querySelectorAll('th')).map((th) => th.textContent!.trim());
    expect(headers(support)).toContain('Support');
    expect(headers(support)).not.toContain('Resistance');
    expect(headers(resistance)).toContain('Resistance');
    expect(headers(resistance)).not.toContain('Support');
    expect(headers(support)).toContain('Margin');

    // AAPL 200 is below the 220 support → broken, negative margin
    expect(q(root, 'alerts-support-row-s1')!.getAttribute('data-status')).toBe('below');
    expect(q(root, 'alerts-support-margin-s1')!.textContent).toContain('-10.00%');
    expect(q(root, 'alerts-resistance-margin-r1')!.textContent).toContain('+20.00%');
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
    http.expectOne('/api/alerts').flush([{ id: 'v1', ticker: 'AAPL', upperLimit: 400, dayHigh: 343.8, dayLow: 335.82,
      lastSessionVolume: 64_757_691, lastSessionDate: '2026-11-02', createdAt: '2026-11-03T10:00:00Z' }]);
    TestBed.inject(PriceStreamService).apply({ ticker: 'AAPL', price: 345.1, previousPrice: 344, timestamp: '2026-11-03T15:00:00Z', direction: 'up' });
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    expect(q(root, 'alerts-resistance-daylow-v1')!.textContent).toContain('335.82');
    expect(q(root, 'alerts-resistance-dayhigh-v1')!.textContent).toContain('345.10');
    expect(q(root, 'alerts-resistance-volume-v1')!.textContent).toContain('64.76M');
    expect(q(root, 'alerts-resistance-volume-v1')!.getAttribute('title')).toContain('2 Nov');
  });

  it('deletes an alert', async () => {
    const fixture = TestBed.createComponent(AlertsPage);
    fixture.detectChanges();
    http.expectOne('/api/alerts').flush([{ id: 'd1', ticker: 'AAPL', lowerLimit: 100, createdAt: '2026-11-03T10:00:00Z' }]);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    q(root, 'alerts-support-delete-d1')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/alerts/d1' }).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(q(root, 'alerts-support-row-d1')).toBeNull();
  });
});
