import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fakeEventSourceFactory } from '../../../testing/fake-event-source';
import { KanbanCard } from '../../api/models';
import { EVENT_SOURCE_FACTORY } from '../../core/event-source';
import { KanbanService } from '../../core/kanban.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { KanbanCardComponent } from './kanban-card.component';

const card: KanbanCard = {
  id: 'c1',
  ticker: 'AAPL',
  column: 'SUPPORT',
  targetPrice: 185,
  sortOrder: 0,
  armed: false,
  status: 'NOTIFIED',
  createdAt: '2026-11-02T10:00:00Z',
  updatedAt: '2026-11-02T10:00:00Z',
};

describe('KanbanCardComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [KanbanCardComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(KanbanService).cards.set([card]);
  });

  afterEach(() => http.verify());

  function setup() {
    const fixture = TestBed.createComponent(KanbanCardComponent);
    fixture.componentRef.setInput('card', card);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const input = root.querySelector('[data-testid="kanban-card-price-c1"]') as HTMLInputElement;
    return { fixture, root, input };
  }

  it('shows ticker, live price and target; armed state on host', () => {
    const { fixture, root, input } = setup();
    TestBed.inject(PriceStreamService).apply({
      ticker: 'AAPL',
      price: 190.12,
      previousPrice: 190,
      timestamp: new Date().toISOString(),
      direction: 'up',
    });
    fixture.detectChanges();
    expect(root.textContent).toContain('AAPL');
    expect(root.querySelector('[data-testid="kanban-card-live-c1"]')!.textContent).toContain('190.12');
    expect(input.value).toBe('185.00');
    expect(root.getAttribute('data-armed')).toBe('false');
  });

  it('shows a NOTIFIED badge only once the alert fired', () => {
    const { fixture, root } = setup(); // fixture card: armed = false (notified)
    const badge = () => root.querySelector('[data-testid="kanban-card-status-c1"]');
    expect(badge()?.textContent?.trim()).toBe('Notified');
    fixture.componentRef.setInput('card', { ...card, armed: true, status: 'ACTIVE' });
    fixture.detectChanges();
    expect(badge()).toBeNull();
  });

  it('rejects invalid prices without a request', () => {
    const { fixture, root, input } = setup();
    input.value = '-3';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="kanban-card-error-c1"]')!.textContent).toContain('> 0');
    http.expectNone('/api/kanban/c1');
  });

  it('PATCHes a valid price on blur', () => {
    const { fixture, input } = setup();
    input.value = '180.5';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/kanban/c1' });
    expect(req.request.body).toEqual({ targetPrice: 180.5 });
    req.flush({ ...card, targetPrice: 180.5, armed: true, status: 'ACTIVE' });
    fixture.detectChanges();
    expect(TestBed.inject(KanbanService).cards()[0].armed).toBe(true);
  });

  it('edits the comment: PATCHes only the comment on blur, skips unchanged, Escape cancels', () => {
    const { fixture, root } = setup();
    const comment = root.querySelector('[data-testid="kanban-card-comment-c1"]') as HTMLInputElement;
    expect(comment.value).toBe('');

    comment.dispatchEvent(new Event('blur')); // unchanged: no request
    comment.value = '  buy the dip ';
    comment.dispatchEvent(new Event('input'));
    comment.dispatchEvent(new Event('blur'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/kanban/c1' });
    expect(req.request.body).toEqual({ comment: 'buy the dip' });
    req.flush({ ...card, comment: 'buy the dip' });
    fixture.detectChanges();
    expect(comment.value).toBe('buy the dip');
    expect(TestBed.inject(KanbanService).cards()[0].comment).toBe('buy the dip');
    fixture.componentRef.setInput('card', TestBed.inject(KanbanService).cards()[0]); // as the board does
    fixture.detectChanges();

    comment.value = 'oops';
    comment.dispatchEvent(new Event('input'));
    fixture.detectChanges(); // the browser renders after typing
    comment.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(comment.value).toBe('buy the dip'); // the blur after Escape sends nothing (verified in afterEach)
  });

  it('keeps a target being typed when a comment save returns a new card object', () => {
    const { fixture, root, input } = setup();
    input.value = '170';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.componentRef.setInput('card', { ...card, comment: 'saved elsewhere' }); // same target, new object
    fixture.detectChanges();
    expect(input.value).toBe('170');
    expect((root.querySelector('[data-testid="kanban-card-comment-c1"]') as HTMLInputElement).value).toBe('saved elsewhere');
  });

  it('highlights a Resistance card whose target is below the current price', () => {
    const fixture = TestBed.createComponent(KanbanCardComponent);
    fixture.componentRef.setInput('card', { ...card, column: 'RESISTANCE', targetPrice: 300, currentPrice: 310 });
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(host.getAttribute('data-broken')).toBe('true');
    expect(host.getAttribute('title')).toContain('above this resistance');

    fixture.componentRef.setInput('card', { ...card, column: 'RESISTANCE', targetPrice: 320, currentPrice: 310 });
    fixture.detectChanges();
    expect(host.getAttribute('data-broken')).toBe('false');

    // a Support card below the price is the normal case: no highlight
    fixture.componentRef.setInput('card', { ...card, column: 'SUPPORT', targetPrice: 300, currentPrice: 310 });
    fixture.detectChanges();
    expect(host.getAttribute('data-broken')).toBe('false');
  });

  it('clears the comment with an empty value', () => {
    TestBed.inject(KanbanService).cards.set([{ ...card, comment: 'old' }]);
    const fixture = TestBed.createComponent(KanbanCardComponent);
    fixture.componentRef.setInput('card', { ...card, comment: 'old' });
    fixture.detectChanges();
    const comment = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="kanban-card-comment-c1"]') as HTMLInputElement;
    comment.value = '';
    comment.dispatchEvent(new Event('input'));
    comment.dispatchEvent(new Event('blur'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/kanban/c1' });
    expect(req.request.body).toEqual({ comment: '' });
    req.flush({ ...card, comment: null });
  });
});
