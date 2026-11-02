import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fakeEventSourceFactory } from '../../testing/fake-event-source';
import { WatchlistItem, errorMessage } from '../api/models';
import { EVENT_SOURCE_FACTORY } from './event-source';
import { WatchlistService, changePercent } from './watchlist.service';

const item = (ticker: string): WatchlistItem => ({ ticker, price: 100, sessionOpenPrice: 100, addedAt: '2026-11-02T10:00:00Z' });

describe('WatchlistService', () => {
  let service: WatchlistService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    service = TestBed.inject(WatchlistService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the watchlist', () => {
    service.load();
    http.expectOne({ method: 'GET', url: '/api/watchlist' }).flush([item('AAPL'), item('MSFT')]);
    expect(service.items().map((i) => i.ticker)).toEqual(['AAPL', 'MSFT']);
  });

  it('adds a normalized ticker via POST', () => {
    service.add('  pypl ').subscribe();
    const req = http.expectOne({ method: 'POST', url: '/api/watchlist' });
    expect(req.request.body).toEqual({ ticker: 'PYPL' });
    req.flush(item('PYPL'), { status: 201, statusText: 'Created' });
    expect(service.items().map((i) => i.ticker)).toEqual(['PYPL']);
  });

  it('surfaces 409 conflict messages', () => {
    let msg = '';
    service.add('AAPL').subscribe({ error: (e) => (msg = errorMessage(e)) });
    http
      .expectOne('/api/watchlist')
      .flush({ error: 'DUPLICATE', message: 'AAPL is already on the watchlist' }, { status: 409, statusText: 'Conflict' });
    expect(msg).toBe('AAPL is already on the watchlist');
  });

  it('removes a ticker via DELETE', () => {
    service.items.set([item('AAPL'), item('MSFT')]);
    service.remove('AAPL').subscribe();
    http.expectOne({ method: 'DELETE', url: '/api/watchlist/AAPL' }).flush(null, { status: 204, statusText: 'No Content' });
    expect(service.items().map((i) => i.ticker)).toEqual(['MSFT']);
  });

  it('computes change percent', () => {
    expect(changePercent(110, 100)).toBeCloseTo(10);
    expect(changePercent(90, 100)).toBeCloseTo(-10);
    expect(changePercent(null, 100)).toBeNull();
    expect(changePercent(10, 0)).toBeNull();
  });
});
