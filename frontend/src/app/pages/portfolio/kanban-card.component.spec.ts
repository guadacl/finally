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
    req.flush({ ...card, targetPrice: 180.5, armed: true });
    fixture.detectChanges();
    expect(TestBed.inject(KanbanService).cards()[0].armed).toBe(true);
  });
});
