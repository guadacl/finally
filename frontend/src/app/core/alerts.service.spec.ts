import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PriceAlert } from '../api/models';
import { fmtVolume } from '../shared/format';
import { AlertsService, alertMargin, alertStatus, buildAlert, liveDayRange } from './alerts.service';

const alert = (id: string, ticker = 'AAPL'): PriceAlert => ({ id, ticker, upperLimit: 200, lowerLimit: 150, createdAt: '2026-11-03T10:00:00Z' });

describe('buildAlert', () => {
  it('builds a request with trimmed comment and numeric limits', () => {
    expect(buildAlert('AAPL', '200', ' 150,5 ', '  earnings  ')).toEqual({ ticker: 'AAPL', upperLimit: 200, lowerLimit: 150.5, comment: 'earnings' });
  });

  it('accepts a single limit and an empty comment', () => {
    expect(buildAlert('AAPL', '', '150', '')).toEqual({ ticker: 'AAPL', upperLimit: null, lowerLimit: 150, comment: null });
  });

  it('rejects missing stock, no limit, bad numbers, inverted range and long comments', () => {
    expect(buildAlert('', '200', '', '')).toContain('Choose a stock');
    expect(buildAlert('AAPL', '', '', 'x')).toContain('upper limit, a lower limit');
    expect(buildAlert('AAPL', 'abc', '', '')).toContain('Upper limit');
    expect(buildAlert('AAPL', '', '0', '')).toContain('Lower limit');
    expect(buildAlert('AAPL', '100', '100', '')).toContain('below the upper');
    expect(buildAlert('AAPL', '100', '', 'x'.repeat(501))).toContain('500');
  });
});

describe('alertStatus', () => {
  it('compares the live price with the limits', () => {
    expect(alertStatus(null, 100, 200)).toBe('unknown');
    expect(alertStatus(210, 100, 200)).toBe('above');
    expect(alertStatus(200, 100, 200)).toBe('above');
    expect(alertStatus(90, 100, 200)).toBe('below');
    expect(alertStatus(150, 100, 200)).toBe('inside');
    expect(alertStatus(150, null, 200)).toBe('inside');
    expect(alertStatus(50, 100, null)).toBe('below');
  });
});

describe('alertMargin', () => {
  it('is the room left before the level, in % of the price (negative once broken)', () => {
    expect(alertMargin(200, 180, 'support')).toBeCloseTo(10);
    expect(alertMargin(200, 220, 'resistance')).toBeCloseTo(10);
    expect(alertMargin(200, 210, 'support')).toBeCloseTo(-5);
    expect(alertMargin(200, 190, 'resistance')).toBeCloseTo(-5);
    expect(alertMargin(200, 200, 'support')).toBe(0);
  });

  it('is null without a price or a level', () => {
    expect(alertMargin(null, 100, 'support')).toBeNull();
    expect(alertMargin(100, null, 'resistance')).toBeNull();
  });
});

describe('liveDayRange', () => {
  it('widens the server day range with the live price', () => {
    expect(liveDayRange(110, 100, 105)).toEqual({ high: 110, low: 100 });
    expect(liveDayRange(110, 100, 112)).toEqual({ high: 112, low: 100 });
    expect(liveDayRange(110, 100, 99)).toEqual({ high: 110, low: 99 });
    expect(liveDayRange(null, null, 50)).toEqual({ high: 50, low: 50 });
    expect(liveDayRange(null, null, null)).toEqual({ high: null, low: null });
  });
});

describe('fmtVolume', () => {
  it('formats share volume compactly', () => {
    expect(fmtVolume(64_757_691)).toBe('64.76M');
    expect(fmtVolume(950_000)).toBe('950K');
    expect(fmtVolume(1_200_000_000)).toBe('1.2B');
    expect(fmtVolume(21_000_000)).toBe('21M');
    expect(fmtVolume(512)).toBe('512');
    expect(fmtVolume(null)).toBe('—');
  });
});

describe('AlertsService', () => {
  let service: AlertsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AlertsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads, creates and deletes alerts', () => {
    service.load();
    http.expectOne({ method: 'GET', url: '/api/alerts' }).flush([alert('a')]);
    expect(service.alerts().map((a) => a.id)).toEqual(['a']);

    service.create({ ticker: 'MSFT', upperLimit: 600, lowerLimit: null, comment: null }).subscribe();
    const post = http.expectOne({ method: 'POST', url: '/api/alerts' });
    expect(post.request.body).toEqual({ ticker: 'MSFT', upperLimit: 600, lowerLimit: null, comment: null });
    post.flush(alert('b', 'MSFT'), { status: 201, statusText: 'Created' });
    expect(service.alerts().map((a) => a.id)).toEqual(['a', 'b']);

    service.remove('a').subscribe();
    http.expectOne({ method: 'DELETE', url: '/api/alerts/a' }).flush(null, { status: 204, statusText: 'No Content' });
    expect(service.alerts().map((a) => a.id)).toEqual(['b']);
  });
});
