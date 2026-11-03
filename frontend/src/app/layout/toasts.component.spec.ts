import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { FakeEventSource, fakeEventSourceFactory } from '../../testing/fake-event-source';
import { Notification } from '../api/models';
import { EVENT_SOURCE_FACTORY } from '../core/event-source';
import { NotificationStreamService } from '../core/notification-stream.service';
import { TOAST_DURATION_MS, ToastService } from '../core/toast.service';
import { ToastsComponent } from './toasts.component';

const hit: Notification = {
  id: 'n1',
  type: 'TARGET_HIT',
  cardId: 'c1',
  ticker: 'AAPL',
  column: 'SUPPORT',
  targetPrice: 185,
  price: 184.97,
  message: 'AAPL hit stop loss 185.00 (now 184.97)',
  createdAt: '2026-11-02T10:00:00Z',
};

describe('ToastsComponent', () => {
  afterEach(() => vi.useRealTimers());

  it('renders stacked toasts and auto-dismisses after ~6s', () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ToastsComponent);
    const toasts = TestBed.inject(ToastService);
    toasts.show({ type: 'TARGET_HIT', ticker: 'AAPL', column: 'SUPPORT', targetPrice: 185, price: 184.97, message: hit.message });
    vi.advanceTimersByTime(1000);
    toasts.show({ type: 'CARD_CREATED', ticker: 'MSFT', column: 'RESISTANCE', targetPrice: 450, message: 'created' });
    fixture.detectChanges();

    const root: HTMLElement = fixture.nativeElement;
    let els = root.querySelectorAll('[data-testid="toast"]');
    expect(els.length).toBe(2);
    expect(els[0].textContent).toContain('AAPL');
    expect(els[0].textContent).toContain('185.00');
    expect(els[0].textContent).toContain('184.97');
    expect(els[0].className).toContain('border-accent');
    expect(els[0].textContent).toContain('Stop Loss');
    expect(els[1].textContent).toContain('Take Profit');
    expect(els[0].textContent).not.toContain('SUPPORT');

    vi.advanceTimersByTime(TOAST_DURATION_MS - 1000);
    fixture.detectChanges();
    els = root.querySelectorAll('[data-testid="toast"]');
    expect(els.length).toBe(1);
    expect(els[0].textContent).toContain('MSFT');

    vi.advanceTimersByTime(1000);
    fixture.detectChanges();
    expect(root.querySelectorAll('[data-testid="toast"]').length).toBe(0);
  });

  it('closes a toast with the close button', () => {
    const fixture = TestBed.createComponent(ToastsComponent);
    TestBed.inject(ToastService).show({ type: 'INFO', message: 'hello' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('[data-testid="toast-close"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelectorAll('[data-testid="toast"]').length).toBe(0);
  });
});

describe('NotificationStreamService', () => {
  beforeEach(() => {
    FakeEventSource.reset();
    TestBed.configureTestingModule({ providers: [{ provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory }] });
  });

  it('emits parsed notifications once per id', () => {
    const svc = TestBed.inject(NotificationStreamService);
    const got: Notification[] = [];
    const sub = new Subject<void>();
    svc.notifications$.subscribe((n) => got.push(n));
    svc.connect();
    const es = FakeEventSource.last();
    expect(es.url).toBe('/api/stream/notifications');
    es.emit('notification', hit);
    es.emit('notification', hit);
    es.emit('notification', 'garbage');
    expect(got).toEqual([hit]);
    svc.disconnect();
    expect(es.closed).toBe(true);
    sub.complete();
  });
});
