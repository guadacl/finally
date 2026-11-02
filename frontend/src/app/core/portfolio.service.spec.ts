import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fakeEventSourceFactory } from '../../testing/fake-event-source';
import { Portfolio, Position } from '../api/models';
import { EVENT_SOURCE_FACTORY } from './event-source';
import { PortfolioService, livePositions, totalValue } from './portfolio.service';
import { PriceStreamService } from './price-stream.service';

const pos = (ticker: string, quantity: number, avgCost: number, currentPrice: number): Position => ({
  ticker,
  quantity,
  avgCost,
  currentPrice,
  marketValue: quantity * currentPrice,
  unrealizedPnl: quantity * (currentPrice - avgCost),
  unrealizedPnlPercent: ((currentPrice - avgCost) / avgCost) * 100,
});

describe('portfolio calculations', () => {
  it('re-prices positions with live prices and falls back to server price', () => {
    const out = livePositions([pos('AAPL', 10, 100, 100), pos('MSFT', 2, 50, 55)], (t) => (t === 'AAPL' ? 110 : null));
    expect(out[0].currentPrice).toBe(110);
    expect(out[0].marketValue).toBe(1100);
    expect(out[0].unrealizedPnl).toBe(100);
    expect(out[0].unrealizedPnlPercent).toBeCloseTo(10);
    expect(out[1].currentPrice).toBe(55);
    expect(out[1].unrealizedPnl).toBe(10);
  });

  it('handles losses and fractional shares', () => {
    const [p] = livePositions([pos('TSLA', 0.5, 200, 200)], () => 150);
    expect(p.unrealizedPnl).toBe(-25);
    expect(p.unrealizedPnlPercent).toBeCloseTo(-25);
  });

  it('total = cash + sum(qty * price)', () => {
    expect(totalValue(1000, [pos('A', 2, 10, 15), pos('B', 1, 5, 5)])).toBe(1035);
    expect(totalValue(10000, [])).toBe(10000);
  });
});

describe('PortfolioService', () => {
  let service: PortfolioService;
  let http: HttpTestingController;
  let prices: PriceStreamService;

  const portfolio: Portfolio = {
    cashBalance: 5000,
    positionsValue: 1000,
    totalValue: 6000,
    unrealizedPnl: 0,
    positions: [pos('AAPL', 10, 100, 100)],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    service = TestBed.inject(PortfolioService);
    http = TestBed.inject(HttpTestingController);
    prices = TestBed.inject(PriceStreamService);
  });

  afterEach(() => http.verify());

  it('loads the portfolio and computes a live total from SSE prices', () => {
    service.load();
    http.expectOne('/api/portfolio').flush(portfolio);
    expect(service.cash()).toBe(5000);
    expect(service.total()).toBe(6000);
    prices.apply({ ticker: 'AAPL', price: 120, previousPrice: 100, timestamp: new Date().toISOString(), direction: 'up' });
    expect(service.total()).toBe(6200);
    expect(service.unrealizedPnl()).toBe(200);
  });

  it('posts trades and adopts the returned portfolio', () => {
    let done = false;
    service.trade({ ticker: 'AAPL', quantity: 1, side: 'buy' }).subscribe(() => (done = true));
    const req = http.expectOne('/api/portfolio/trade');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ ticker: 'AAPL', quantity: 1, side: 'buy' });
    req.flush({
      trade: { id: '1', ticker: 'AAPL', side: 'buy', quantity: 1, price: 100, executedAt: '' },
      portfolio,
    });
    http.expectOne('/api/portfolio/history').flush([]);
    expect(done).toBe(true);
    expect(service.portfolio()).toEqual(portfolio);
  });
});
