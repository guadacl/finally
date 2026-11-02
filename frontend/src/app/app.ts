import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { ChatService } from './core/chat.service';
import { KanbanService } from './core/kanban.service';
import { NotificationStreamService } from './core/notification-stream.service';
import { PortfolioService } from './core/portfolio.service';
import { PriceStreamService } from './core/price-stream.service';
import { ToastService } from './core/toast.service';
import { WatchlistService } from './core/watchlist.service';
import { ChatPanelComponent } from './layout/chat-panel.component';
import { HeaderComponent } from './layout/header.component';
import { ToastsComponent } from './layout/toasts.component';

const PORTFOLIO_REFRESH_MS = 30_000;

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, HeaderComponent, ChatPanelComponent, ToastsComponent],
  host: { class: 'flex h-screen flex-col overflow-hidden' },
  template: `
    <app-header />
    <div class="flex min-h-0 flex-1">
      <main class="min-w-0 flex-1 overflow-auto p-2">
        <router-outlet />
      </main>
      <app-chat-panel />
    </div>
    <app-toasts />
  `,
})
export class App implements OnInit {
  private readonly prices = inject(PriceStreamService);
  private readonly notifications = inject(NotificationStreamService);
  private readonly portfolio = inject(PortfolioService);
  private readonly watchlist = inject(WatchlistService);
  private readonly kanban = inject(KanbanService);
  private readonly chat = inject(ChatService);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.prices.connect();
    this.notifications.connect();
    this.portfolio.load();
    this.portfolio.loadHistory();
    this.watchlist.load();
    this.kanban.load();
    this.chat.loadHistory();

    this.notifications.notifications$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((n) => {
      this.toasts.show({
        type: n.type,
        ticker: n.ticker,
        column: n.column,
        targetPrice: n.targetPrice,
        price: n.price ?? this.prices.price(n.ticker),
        message: n.message,
      });
      if (n.type === 'TARGET_HIT') this.kanban.load();
    });

    const timer = setInterval(() => {
      this.portfolio.load();
      this.portfolio.loadHistory();
    }, PORTFOLIO_REFRESH_MS);

    this.destroyRef.onDestroy(() => {
      clearInterval(timer);
      this.prices.disconnect();
      this.notifications.disconnect();
    });
  }
}
