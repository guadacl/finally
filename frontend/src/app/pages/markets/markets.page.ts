import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { PriceStreamService } from '../../core/price-stream.service';
import { WatchlistService, changePercent } from '../../core/watchlist.service';
import { PctPipe, PricePipe, signClass } from '../../shared/format';
import { LineChartComponent } from '../../shared/line-chart.component';
import { PriceFlashDirective } from '../../shared/price-flash.directive';
import { TradeBarComponent } from '../../shared/trade-bar.component';
import { WatchlistTableComponent } from '../../shared/watchlist-table.component';

@Component({
  selector: 'app-markets-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WatchlistTableComponent, LineChartComponent, TradeBarComponent, PricePipe, PctPipe, PriceFlashDirective],
  host: { class: 'grid h-full min-h-[560px] gap-2 grid-cols-1 lg:grid-cols-[minmax(340px,420px)_1fr]' },
  template: `
    <section class="panel flex min-h-[280px] flex-col overflow-hidden">
      <div class="panel-title">
        <span>Watchlist</span>
        <span class="num normal-case tracking-normal">{{ watchlist.items().length }} tickers</span>
      </div>
      <app-watchlist-table class="min-h-0 flex-1" [selectable]="true" [selected]="selected()" (pick)="selected.set($event)" />
    </section>

    <section class="flex min-h-0 flex-col gap-2">
      <div class="panel flex min-h-[300px] flex-1 flex-col overflow-hidden">
        <div class="flex items-baseline gap-4 border-b border-edge px-3 py-2">
          <span class="text-lg font-bold tracking-wide" data-testid="chart-ticker">{{ selected() ?? '—' }}</span>
          <span class="num rounded px-1 text-lg" [appPriceFlash]="state()?.price">{{ state()?.price | price }}</span>
          <span class="num text-sm" [class]="changeClass()">{{ change() | pct }}</span>
          <span class="ml-auto text-[10px] tracking-widest text-muted uppercase">Since page load · live SSE</span>
        </div>
        <app-line-chart
          class="min-h-0 flex-1"
          data-testid="main-chart"
          [points]="state()?.history ?? []"
          [resetKey]="selected() ?? ''"
          [color]="chartColor()"
        />
      </div>
      <div class="panel">
        <app-trade-bar [presetTicker]="selected()" />
      </div>
    </section>
  `,
})
export class MarketsPage {
  readonly watchlist = inject(WatchlistService);
  private readonly prices = inject(PriceStreamService);

  readonly selected = signal<string | null>(null);
  readonly state = computed(() => {
    const t = this.selected();
    return t ? this.prices.state(t)() : null;
  });
  readonly change = computed(() => {
    const t = this.selected();
    const s = this.state();
    if (!t || !s) return null;
    const item = this.watchlist.items().find((i) => i.ticker === t);
    return changePercent(s.price, item ? this.watchlist.sessionOpen(item) : s.firstPrice);
  });
  readonly changeClass = computed(() => signClass(this.change()));
  readonly chartColor = computed(() => ((this.change() ?? 0) >= 0 ? '#209dd7' : '#ef5350'));

  constructor() {
    // Default selection: first watchlist ticker; re-select if the selected one is removed.
    effect(() => {
      const items = this.watchlist.items();
      const sel = this.selected();
      if (items.length && (!sel || !items.some((i) => i.ticker === sel))) {
        this.selected.set(items[0].ticker);
      }
    });
  }
}
