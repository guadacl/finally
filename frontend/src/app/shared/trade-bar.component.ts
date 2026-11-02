import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TradeSide, errorMessage } from '../api/models';
import { PortfolioService } from '../core/portfolio.service';
import { PriceStreamService } from '../core/price-stream.service';
import { normalizeTicker } from '../core/watchlist.service';
import { MoneyPipe, PricePipe } from './format';

@Component({
  selector: 'app-trade-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MoneyPipe, PricePipe],
  template: `
    <form class="flex flex-wrap items-center gap-2 px-3 py-2" (submit)="$event.preventDefault()">
      <span class="mr-1 text-[10px] font-semibold tracking-widest text-muted uppercase">Trade</span>
      <input
        name="tradeTicker"
        data-testid="trade-ticker"
        class="field num w-24 uppercase"
        placeholder="Ticker"
        autocomplete="off"
        maxlength="10"
        [ngModel]="ticker()"
        (ngModelChange)="ticker.set($event); clear()"
      />
      <input
        name="tradeQty"
        data-testid="trade-quantity"
        class="field num w-24"
        type="number"
        min="0"
        step="any"
        placeholder="Qty"
        [ngModel]="quantity()"
        (ngModelChange)="quantity.set($event); clear()"
      />
      <span class="num w-24 text-xs text-muted">
        @if (livePrice() != null) {
          &#64; {{ livePrice() | price }}
        }
      </span>
      <span class="num hidden w-28 text-xs text-muted md:inline">
        @if (estimate() != null) {
          ≈ {{ estimate() | money }}
        }
      </span>
      <button type="button" data-testid="trade-buy" class="btn btn-buy" [disabled]="busy()" (click)="submit('buy')">Buy</button>
      <button type="button" data-testid="trade-sell" class="btn btn-sell" [disabled]="busy()" (click)="submit('sell')">Sell</button>
      @if (error(); as e) {
        <span data-testid="trade-error" class="text-xs text-down">{{ e }}</span>
      }
      @if (success(); as s) {
        <span data-testid="trade-success" class="text-xs text-up">{{ s }}</span>
      }
    </form>
  `,
})
export class TradeBarComponent {
  private readonly portfolio = inject(PortfolioService);
  private readonly prices = inject(PriceStreamService);

  /** Pre-fills the ticker field (e.g. the selected watchlist row). */
  readonly presetTicker = input<string | null>(null);

  readonly ticker = signal('');
  readonly quantity = signal<number | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);

  readonly livePrice = computed(() => {
    const t = normalizeTicker(this.ticker() ?? '');
    return t ? this.prices.price(t) : null;
  });
  readonly estimate = computed(() => {
    const p = this.livePrice();
    const q = Number(this.quantity());
    return p != null && q > 0 ? p * q : null;
  });

  constructor() {
    effect(() => {
      const t = this.presetTicker();
      if (t) this.ticker.set(t);
    });
  }

  clear(): void {
    this.error.set(null);
    this.success.set(null);
  }

  submit(side: TradeSide): void {
    const ticker = normalizeTicker(this.ticker() ?? '');
    const quantity = Number(this.quantity());
    this.clear();
    if (!ticker) {
      this.error.set('Enter a ticker');
      return;
    }
    if (!Number.isFinite(quantity) || quantity <= 0) {
      this.error.set('Quantity must be greater than 0');
      return;
    }
    this.busy.set(true);
    this.portfolio.trade({ ticker, quantity, side }).subscribe({
      next: (res) => {
        this.busy.set(false);
        const t = res.trade;
        this.success.set(`${t.side === 'buy' ? 'Bought' : 'Sold'} ${t.quantity} ${t.ticker} @ ${t.price.toFixed(2)}`);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Trade failed'));
      },
    });
  }
}
