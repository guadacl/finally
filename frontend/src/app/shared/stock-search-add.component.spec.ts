import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { TickerSearchResult } from '../api/models';
import { WatchlistService } from '../core/watchlist.service';
import { SEARCH_DEBOUNCE_MS, StockSearchAddComponent } from './stock-search-add.component';

const hit = (ticker: string, name: string, exchange = 'XNAS'): TickerSearchResult => ({ ticker, name, exchange, type: 'CS' });

describe('StockSearchAddComponent', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<StockSearchAddComponent>;
  let root: HTMLElement;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(WatchlistService).items.set([{ ticker: 'AAPL', name: 'Apple Inc.', addedAt: '2026-11-06T10:00:00Z' }]);
    fixture = TestBed.createComponent(StockSearchAddComponent);
    fixture.detectChanges();
    root = fixture.nativeElement;
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const q = (id: string) => root.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const type = (value: string) => {
    const input = q('watchlist-add-input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const enter = () => {
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
  };
  const searchReq = (query: string) => http.expectOne((r) => r.url === '/api/tickers/search' && r.params.get('q') === query);
  const addReq = () => http.expectOne({ method: 'POST', url: '/api/watchlist' });

  it('adds the only match on Enter (search by name)', () => {
    type('enphase');
    enter();
    searchReq('enphase').flush([hit('ENPH', 'Enphase Energy, Inc.')]);
    fixture.detectChanges();
    const add = addReq();
    expect(add.request.body).toEqual({ ticker: 'ENPH' });
    add.flush({ ticker: 'ENPH', name: 'Enphase Energy, Inc.', addedAt: '2026-11-06T10:00:00Z' }, { status: 201, statusText: 'Created' });
    fixture.detectChanges();
    expect((q('watchlist-add-input') as HTMLInputElement).value).toBe('');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS); // the typeahead for "enphase" is answered from the same query: drop it
    http.match(() => true).forEach((r) => r.flush([]));
  });

  it('lets the user pick when several stocks match, and greys out the ones already watched', () => {
    type('ap');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS); // typeahead after the pause
    searchReq('ap').flush([hit('AAPL', 'Apple Inc.'), hit('APP', 'AppLovin Corp'), hit('APA', 'APA Corp', 'XNYS')]);
    fixture.detectChanges();
    expect(q('watchlist-suggestions')).not.toBeNull();
    expect(q('watchlist-pick-hint')!.textContent).toContain('3 stocks found');
    expect(q('watchlist-suggestion-AAPL')!.textContent).toContain('in watchlist');
    expect(q('watchlist-suggestion-APP')!.textContent).toContain('AppLovin Corp');

    enter(); // two selectable matches: no add, the list stays open with a hint
    http.expectNone({ method: 'POST', url: '/api/watchlist' });
    expect(q('watchlist-search-info')!.textContent).toContain('2 stocks match');

    q('watchlist-suggestion-AAPL')!.click(); // already watched
    fixture.detectChanges();
    http.expectNone({ method: 'POST', url: '/api/watchlist' });
    expect(q('watchlist-error')!.textContent).toContain('AAPL is already in your watchlist');

    q('watchlist-suggestion-APA')!.click();
    expect(addReq().request.body).toEqual({ ticker: 'APA' });
  });

  it('picks the highlighted suggestion with the arrow keys', () => {
    type('ap');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    searchReq('ap').flush([hit('APP', 'AppLovin Corp'), hit('APA', 'APA Corp')]);
    fixture.detectChanges();
    const input = q('watchlist-add-input') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    fixture.detectChanges();
    expect(q('watchlist-suggestion-APA')!.getAttribute('aria-selected')).toBe('true');
    enter();
    expect(addReq().request.body).toEqual({ ticker: 'APA' });
  });

  it('adds the typed ticker when the search is unavailable', () => {
    type('pypl');
    enter();
    searchReq('pypl').flush({ error: 'SEARCH_UNAVAILABLE', message: 'Stock search is unavailable right now.' },
      { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    expect(addReq().request.body).toEqual({ ticker: 'PYPL' });
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.match(() => true).forEach((r) => r.flush([]));
  });

  it('explains when nothing matches a name', () => {
    type('no such company');
    enter();
    searchReq('no such company').flush([]);
    fixture.detectChanges();
    http.expectNone({ method: 'POST', url: '/api/watchlist' });
    expect(q('watchlist-error')!.textContent).toContain('No stock found for "no such company"');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.match(() => true).forEach((r) => r.flush([]));
  });
});
