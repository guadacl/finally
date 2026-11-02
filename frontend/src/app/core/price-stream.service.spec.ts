import { TestBed } from '@angular/core/testing';
import { FakeEventSource, fakeEventSourceFactory } from '../../testing/fake-event-source';
import { ES_CLOSED, ES_CONNECTING, EVENT_SOURCE_FACTORY } from './event-source';
import { MAX_HISTORY, PriceStreamService, parsePriceUpdate } from './price-stream.service';

describe('parsePriceUpdate', () => {
  it('parses a valid contract payload', () => {
    const u = parsePriceUpdate(
      '{"ticker":"AAPL","price":190.12,"previousPrice":190.05,"timestamp":"2026-11-02T10:00:00.500Z","direction":"up"}',
    );
    expect(u).toEqual({
      ticker: 'AAPL',
      price: 190.12,
      previousPrice: 190.05,
      timestamp: '2026-11-02T10:00:00.500Z',
      direction: 'up',
    });
  });

  it('derives direction when missing and rejects garbage', () => {
    expect(parsePriceUpdate('{"ticker":"x","price":9,"previousPrice":10}')?.direction).toBe('down');
    expect(parsePriceUpdate('{"ticker":"X"}')).toBeNull();
    expect(parsePriceUpdate('not json')).toBeNull();
  });
});

describe('PriceStreamService', () => {
  let service: PriceStreamService;

  beforeEach(() => {
    FakeEventSource.reset();
    TestBed.configureTestingModule({
      providers: [{ provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory }],
    });
    service = TestBed.inject(PriceStreamService);
  });

  afterEach(() => vi.useRealTimers());

  it('opens a single EventSource on /api/stream/prices', () => {
    service.connect();
    service.connect();
    expect(FakeEventSource.instances.length).toBe(1);
    expect(FakeEventSource.last().url).toBe('/api/stream/prices');
  });

  it('tracks connection status', () => {
    service.connect();
    const es = FakeEventSource.last();
    expect(service.status()).toBe('reconnecting');
    es.open();
    expect(service.status()).toBe('connected');
    es.fail(ES_CONNECTING);
    expect(service.status()).toBe('reconnecting');
    es.open();
    expect(service.status()).toBe('connected');
  });

  it('marks disconnected and retries when the browser gives up', () => {
    vi.useFakeTimers();
    service.connect();
    const es = FakeEventSource.last();
    es.fail(ES_CLOSED);
    expect(service.status()).toBe('disconnected');
    vi.advanceTimersByTime(3000);
    expect(FakeEventSource.instances.length).toBe(2);
    expect(service.status()).toBe('reconnecting');
  });

  it('updates per-ticker state from price events', () => {
    service.connect();
    const es = FakeEventSource.last();
    es.emit('price', { ticker: 'AAPL', price: 190, previousPrice: 189, timestamp: '2026-11-02T10:00:00Z', direction: 'up' });
    es.emit('price', { ticker: 'AAPL', price: 189.5, previousPrice: 190, timestamp: '2026-11-02T10:00:01Z', direction: 'down' });
    const s = service.state('aapl')();
    expect(s?.price).toBe(189.5);
    expect(s?.previousPrice).toBe(190);
    expect(s?.direction).toBe('down');
    expect(s?.firstPrice).toBe(190);
    expect(s?.history.map((p) => p.value)).toEqual([190, 189.5]);
    expect(service.price('MSFT')).toBeNull();
  });

  it('bounds the history length', () => {
    for (let i = 0; i < MAX_HISTORY + 50; i++) {
      service.apply({ ticker: 'TSLA', price: i + 1, previousPrice: i, timestamp: new Date(i * 1000).toISOString(), direction: 'up' });
    }
    const h = service.state('TSLA')()!.history;
    expect(h.length).toBe(MAX_HISTORY);
    expect(h[h.length - 1].value).toBe(MAX_HISTORY + 50);
    expect(h[0].value).toBe(51);
  });

  it('closes the EventSource on disconnect', () => {
    service.connect();
    const es = FakeEventSource.last();
    service.disconnect();
    expect(es.closed).toBe(true);
    expect(service.status()).toBe('disconnected');
  });
});
