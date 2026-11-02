import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fakeEventSourceFactory } from '../../testing/fake-event-source';
import { EVENT_SOURCE_FACTORY } from '../core/event-source';
import { ChatPanelComponent } from './chat-panel.component';

describe('ChatPanelComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ChatPanelComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const q = (root: HTMLElement, id: string) => root.querySelectorAll(`[data-testid="${id}"]`);

  it('shows loading while waiting and renders the reply with action chips', async () => {
    const fixture = TestBed.createComponent(ChatPanelComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;

    fixture.componentInstance.draft.set('buy 1 AAPL');
    fixture.componentInstance.send();
    fixture.detectChanges();

    expect(q(root, 'chat-loading').length).toBe(1);
    expect(q(root, 'chat-message').length).toBe(1);

    const req = http.expectOne({ method: 'POST', url: '/api/chat' });
    expect(req.request.body).toEqual({ message: 'buy 1 AAPL' });
    req.flush({
      id: 'm1',
      message: 'Bought 1 AAPL for you.',
      createdAt: '2026-11-02T10:00:00Z',
      actions: {
        trades: [
          { ticker: 'AAPL', side: 'buy', quantity: 1, price: 190, status: 'executed', error: null },
          { ticker: 'TSLA', side: 'sell', quantity: 5, price: null, status: 'failed', error: 'Insufficient shares' },
        ],
        watchlistChanges: [{ ticker: 'PYPL', action: 'add', status: 'executed', error: null }],
      },
    });
    // actions trigger a refresh of portfolio + watchlist
    http.expectOne('/api/portfolio').flush({ cashBalance: 0, positionsValue: 0, totalValue: 0, unrealizedPnl: 0, positions: [] });
    http.expectOne('/api/portfolio/history').flush([]);
    http.expectOne('/api/watchlist').flush([]);
    fixture.detectChanges();

    expect(q(root, 'chat-loading').length).toBe(0);
    const messages = q(root, 'chat-message');
    expect(messages.length).toBe(2);
    expect(messages[1].textContent).toContain('Bought 1 AAPL for you.');
    const chips = Array.from(q(root, 'chat-action')) as HTMLElement[];
    expect(chips.length).toBe(3);
    expect(chips[0].dataset['status']).toBe('executed');
    expect(chips[1].dataset['status']).toBe('failed');
    expect(chips[1].textContent).toContain('Insufficient shares');
  });

  it('shows an error when the LLM call fails', () => {
    const fixture = TestBed.createComponent(ChatPanelComponent);
    fixture.componentInstance.draft.set('hello');
    fixture.componentInstance.send();
    http
      .expectOne('/api/chat')
      .flush({ error: 'LLM_ERROR', message: 'LLM call failed' }, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(q(root, 'chat-loading').length).toBe(0);
    expect(q(root, 'chat-error')[0].textContent).toContain('LLM call failed');
  });
});
