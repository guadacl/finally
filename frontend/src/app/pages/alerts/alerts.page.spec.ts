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
    const row = q(root, 'alerts-row-x1')!;
    expect(row.textContent).toContain('MSFT');
    expect(row.textContent).toContain('earnings week');
    expect(row.getAttribute('data-status')).toBe('inside');
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
