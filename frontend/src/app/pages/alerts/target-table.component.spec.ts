import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeEventSourceFactory } from '../../../testing/fake-event-source';
import { KanbanCard } from '../../api/models';
import { EVENT_SOURCE_FACTORY } from '../../core/event-source';
import { KanbanService } from '../../core/kanban.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { TargetTableComponent } from './target-table.component';

const card = (id: string, column: KanbanCard['column'], ticker: string, targetPrice: number, extra: Partial<KanbanCard> = {}): KanbanCard => ({
  id,
  ticker,
  column,
  targetPrice,
  sortOrder: 0,
  armed: true,
  status: 'ACTIVE',
  createdAt: '2026-11-04T10:00:00Z',
  updatedAt: '2026-11-04T10:00:00Z',
  ...extra,
});

describe('TargetTableComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const prices = TestBed.inject(PriceStreamService);
    for (const [ticker, price] of [['AAPL', 200], ['MSFT', 500], ['NVDA', 100]] as const) {
      prices.apply({ ticker, price, previousPrice: price, timestamp: '2026-11-04T10:00:00Z', direction: 'up' });
    }
    TestBed.inject(KanbanService).cards.set([
      card('s1', 'SUPPORT', 'AAPL', 150, { rsi: 74.25, dayLow: 195, dayHigh: 205, lastSessionVolume: 64_000_000 }), // -25%
      card('s2', 'SUPPORT', 'MSFT', 490, { armed: false, status: 'NOTIFIED', comment: 'earnings' }), // -2%
      card('r1', 'RESISTANCE', 'NVDA', 90, { comment: null }), // below price -> broken
    ]);
  });

  afterEach(() => http.verify());

  function render(column: KanbanCard['column']) {
    const fixture = TestBed.createComponent(TargetTableComponent);
    fixture.componentRef.setInput('column', column);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const q = (id: string) => root.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
    return { fixture, root, q };
  }

  it('has the Price Alerts columns plus target, dist, status and comment', () => {
    const { root } = render('SUPPORT');
    const heads = Array.from(root.querySelectorAll('thead th')).map((th) => th.textContent!.trim()).filter(Boolean);
    expect(heads).toEqual(['Ticker', 'Price', 'Day low', 'Day high', 'Vol (last session)', 'RSI (14)', 'Target', 'Dist', 'Status',
      'Comment', 'Added']);
  });

  it('does not re-sort while a target is being edited (the row would lose focus and save a half-typed value)', () => {
    const { fixture, root, q } = render('SUPPORT');
    const order = () => Array.from(root.querySelectorAll('tbody tr[data-testid^="target-row-"]')).map((r) => r.getAttribute('data-testid'));
    expect(order()).toEqual(['target-row-s2', 'target-row-s1']);
    const input = q('target-price-s2') as HTMLInputElement;
    input.dispatchEvent(new Event('focus'));
    input.value = '18'; // half-typed
    const prices = TestBed.inject(PriceStreamService);
    prices.apply({ ticker: 'AAPL', price: 151, previousPrice: 200, timestamp: '2026-11-04T10:00:01Z', direction: 'down' }); // s1 now nearest
    fixture.detectChanges();
    expect(order()).toEqual(['target-row-s2', 'target-row-s1']);
    expect(input.value).toBe('18');
    // leaving the field saves and re-sorts
    input.value = '480';
    input.dispatchEvent(new Event('blur'));
    http.expectOne({ method: 'PATCH', url: '/api/kanban/s2' }).flush(card('s2', 'SUPPORT', 'MSFT', 480));
    fixture.detectChanges();
    expect(order()).toEqual(['target-row-s1', 'target-row-s2']);
  });

  it('shows a 4-decimal target in full, so focus + blur does not change it', () => {
    TestBed.inject(KanbanService).cards.update((list) => list.map((c) => (c.id === 's1' ? { ...c, targetPrice: 150.1234 } : c)));
    const { q } = render('SUPPORT');
    const input = q('target-price-s1') as HTMLInputElement;
    expect(input.value).toBe('150.1234');
    input.dispatchEvent(new Event('focus'));
    input.dispatchEvent(new Event('blur'));
    http.expectNone({ method: 'PATCH', url: '/api/kanban/s1' });
  });

  it('shows only its column, nearest target first, with market data and status', () => {
    const { root, q } = render('SUPPORT');
    const rows = Array.from(root.querySelectorAll('tbody tr[data-testid^="target-row-"]')).map((r) => r.getAttribute('data-testid'));
    expect(rows).toEqual(['target-row-s2', 'target-row-s1']); // |-2%| before |-25%|; r1 is in the other table
    const s1 = q('target-row-s1')!.textContent!;
    expect(s1).toContain('AAPL');
    expect(s1).toContain('74.3');
    expect(s1).toContain('195.00');
    expect(q('target-status-s1')!.textContent!.trim()).toBe('Active');
    expect(q('target-status-s2')!.textContent!.trim()).toBe('Notified');
    expect((q('target-comment-s2') as HTMLInputElement).value).toBe('earnings');
  });

  it('highlights a Resistance target below the current price', () => {
    const { q } = render('RESISTANCE');
    expect(q('target-row-r1')!.getAttribute('data-broken')).toBe('true');
    expect(q('target-row-r1')!.className).toContain('target-broken');
  });

  it('saves an edited target and comment, moves and deletes', () => {
    const { q } = render('SUPPORT');
    const target = q('target-price-s1') as HTMLInputElement;
    target.value = '160';
    target.dispatchEvent(new Event('blur'));
    const p1 = http.expectOne({ method: 'PATCH', url: '/api/kanban/s1' });
    expect(p1.request.body).toEqual({ targetPrice: 160 });
    p1.flush(card('s1', 'SUPPORT', 'AAPL', 160));

    const comment = q('target-comment-s1') as HTMLInputElement;
    comment.value = 'buy the dip';
    comment.dispatchEvent(new Event('blur'));
    const p2 = http.expectOne({ method: 'PATCH', url: '/api/kanban/s1' });
    expect(p2.request.body).toEqual({ comment: 'buy the dip' });
    p2.flush(card('s1', 'SUPPORT', 'AAPL', 160, { comment: 'buy the dip' }));

    q('target-move-s2')!.click();
    const p3 = http.expectOne({ method: 'PATCH', url: '/api/kanban/s2' });
    expect(p3.request.body).toEqual({ column: 'RESISTANCE' });
    p3.flush(card('s2', 'RESISTANCE', 'MSFT', 490));
    expect(TestBed.inject(KanbanService).resistance().map((c) => c.id)).toContain('s2');

    q('target-delete-s1')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/kanban/s1' }).flush(null, { status: 204, statusText: 'No Content' });
    expect(TestBed.inject(KanbanService).support()).toHaveLength(0);
  });

  it('adds a card to its own column from the + form', async () => {
    const { fixture, q } = render('RESISTANCE');
    q('target-add-button-RESISTANCE')!.click();
    await fixture.whenStable();
    const set = (id: string, v: string) => {
      const el = q(id) as HTMLInputElement;
      el.value = v;
      el.dispatchEvent(new Event('input'));
    };
    set('target-add-ticker-RESISTANCE', 'msft');
    set('target-add-price-RESISTANCE', '600');
    set('target-add-comment-RESISTANCE', 'breakout');
    await fixture.whenStable();
    (q('target-form-RESISTANCE') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const post = http.expectOne({ method: 'POST', url: '/api/kanban' });
    expect(post.request.body).toEqual({ ticker: 'MSFT', column: 'RESISTANCE', targetPrice: 600, comment: 'breakout' });
    post.flush(card('r2', 'RESISTANCE', 'MSFT', 600, { comment: 'breakout' }));
  });
});
