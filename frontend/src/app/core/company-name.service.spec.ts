import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CompanyNameService, NAME_RETRY_MS } from './company-name.service';

describe('CompanyNameService', () => {
  let service: CompanyNameService;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(CompanyNameService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const expectNames = (tickers: string) =>
    http.expectOne((r) => r.url === '/api/reference/names' && r.params.get('tickers') === tickers);

  it('batches the tickers asked for in one tick into a single request', () => {
    expect(service.name('aapl')).toBeNull();
    expect(service.name('MSFT')).toBeNull();
    expect(service.name('AAPL')).toBeNull(); // already queued
    vi.advanceTimersByTime(0);

    expectNames('AAPL,MSFT').flush([
      { ticker: 'AAPL', name: 'Apple Inc.' },
      { ticker: 'MSFT', name: 'Microsoft Corporation' },
    ]);

    expect(service.name('AAPL')).toBe('Apple Inc.');
    expect(service.name('msft')).toBe('Microsoft Corporation');
    vi.advanceTimersByTime(0);
    http.expectNone((r) => r.url === '/api/reference/names'); // known names are not requested again
  });

  it('asks again later for a name that came back unknown', () => {
    service.name('ROKU');
    vi.advanceTimersByTime(0);
    expectNames('ROKU').flush([{ ticker: 'ROKU', name: null }]);

    expect(service.name('ROKU')).toBeNull();
    vi.advanceTimersByTime(0);
    http.expectNone((r) => r.url === '/api/reference/names'); // not before the retry delay

    vi.advanceTimersByTime(NAME_RETRY_MS);
    vi.advanceTimersByTime(1); // the retry queues a batch for the next tick
    expectNames('ROKU').flush([{ ticker: 'ROKU', name: 'Roku, Inc.' }]);
    expect(service.name('ROKU')).toBe('Roku, Inc.');
  });

  it('survives a failed request', () => {
    service.name('AAPL');
    vi.advanceTimersByTime(0);
    expectNames('AAPL').flush('boom', { status: 500, statusText: 'Server Error' });
    expect(service.name('AAPL')).toBeNull();

    vi.advanceTimersByTime(NAME_RETRY_MS);
    vi.advanceTimersByTime(1); // the retry queues a batch for the next tick
    expectNames('AAPL').flush([{ ticker: 'AAPL', name: 'Apple Inc.' }]);
    expect(service.name('AAPL')).toBe('Apple Inc.');
  });
});
