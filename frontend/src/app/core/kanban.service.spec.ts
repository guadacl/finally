import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { isObservable } from 'rxjs';
import { KanbanCard } from '../api/models';
import { KanbanService, targetHint, validateTargetPrice } from './kanban.service';

const card = (id: string, column: KanbanCard['column'], sortOrder: number, targetPrice = 100): KanbanCard => ({
  id,
  ticker: 'AAPL',
  column,
  targetPrice,
  sortOrder,
  armed: true,
  createdAt: `2026-11-02T10:00:0${sortOrder}Z`,
  updatedAt: '2026-11-02T10:00:00Z',
});

describe('kanban validation', () => {
  it('accepts positive numbers', () => {
    expect(validateTargetPrice('185.5')).toEqual({ ok: true, value: 185.5 });
    expect(validateTargetPrice('$1,234.50')).toEqual({ ok: true, value: 1234.5 });
    expect(validateTargetPrice(12)).toEqual({ ok: true, value: 12 });
  });

  it('rejects empty, non-numeric, zero and negative', () => {
    expect(validateTargetPrice('').ok).toBe(false);
    expect(validateTargetPrice('abc').ok).toBe(false);
    expect(validateTargetPrice('0').ok).toBe(false);
    expect(validateTargetPrice('-5').ok).toBe(false);
  });

  it('hints when a target is on the unusual side of the price', () => {
    expect(targetHint('SUPPORT', 200, 190)).toContain('above');
    expect(targetHint('SUPPORT', 180, 190)).toBeNull();
    expect(targetHint('RESISTANCE', 180, 190)).toContain('below');
    expect(targetHint('RESISTANCE', 200, null)).toBeNull();
  });
});

describe('KanbanService', () => {
  let service: KanbanService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(KanbanService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads and splits cards by column, ordered by sortOrder', () => {
    service.load();
    http.expectOne('/api/kanban').flush([card('b', 'SUPPORT', 1), card('r', 'RESISTANCE', 0), card('a', 'SUPPORT', 0)]);
    expect(service.support().map((c) => c.id)).toEqual(['a', 'b']);
    expect(service.resistance().map((c) => c.id)).toEqual(['r']);
  });

  it('creates a card', () => {
    service.create(' nvda ', 'RESISTANCE', 150).subscribe();
    const req = http.expectOne({ method: 'POST', url: '/api/kanban' });
    expect(req.request.body).toEqual({ ticker: 'NVDA', column: 'RESISTANCE', targetPrice: 150 });
    req.flush({ ...card('n', 'RESISTANCE', 0, 150), ticker: 'NVDA' }, { status: 201, statusText: 'Created' });
    expect(service.resistance().length).toBe(1);
  });

  it('PATCHes a valid edited target price and skips invalid/unchanged edits', () => {
    const c = card('a', 'SUPPORT', 0, 100);
    service.cards.set([c]);

    const invalid = service.updateTargetPrice(c, '-1');
    expect(isObservable(invalid)).toBe(false);
    const unchanged = service.updateTargetPrice(c, '100.00');
    expect(isObservable(unchanged)).toBe(false);

    const res = service.updateTargetPrice(c, '95.25');
    expect(isObservable(res)).toBe(true);
    if (isObservable(res)) res.subscribe();
    const req = http.expectOne({ method: 'PATCH', url: '/api/kanban/a' });
    expect(req.request.body).toEqual({ targetPrice: 95.25 });
    req.flush({ ...c, targetPrice: 95.25 });
    expect(service.cards()[0].targetPrice).toBe(95.25);
  });

  it('moves a card across columns with column + sortOrder PATCHes', () => {
    const a = card('a', 'SUPPORT', 0);
    const b = card('b', 'SUPPORT', 1);
    const r = card('r', 'RESISTANCE', 0);
    service.cards.set([a, b, r]);
    // move "a" to the top of RESISTANCE
    service.reorder({ SUPPORT: [b], RESISTANCE: [a, r] }).subscribe();
    expect(service.resistance().map((c) => c.id)).toEqual(['a', 'r']); // optimistic

    const reqs = http.match((req) => req.method === 'PATCH');
    const bodies = Object.fromEntries(reqs.map((q) => [q.request.url, q.request.body]));
    expect(bodies['/api/kanban/a']).toEqual({ column: 'RESISTANCE', sortOrder: 0 });
    expect(bodies['/api/kanban/b']).toEqual({ sortOrder: 0 });
    expect(bodies['/api/kanban/r']).toEqual({ sortOrder: 1 });
    reqs.forEach((q) => q.flush({ ...service.cards().find((c) => q.request.url.endsWith(c.id))! }));
  });

  it('deletes a card', () => {
    service.cards.set([card('a', 'SUPPORT', 0)]);
    service.delete('a').subscribe();
    http.expectOne({ method: 'DELETE', url: '/api/kanban/a' }).flush(null, { status: 204, statusText: 'No Content' });
    expect(service.cards()).toEqual([]);
  });
});
