import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { PortfolioService } from '../../core/portfolio.service';
import { PricePoint } from '../../core/price-stream.service';
import { MoneyPipe, PctPipe, PricePipe, QtyPipe, signClass } from '../../shared/format';
import { LineChartComponent } from '../../shared/line-chart.component';
import { PriceFlashDirective } from '../../shared/price-flash.directive';
import { HeatmapComponent } from './heatmap.component';
import { KanbanBoardComponent } from './kanban-board.component';
import { CompanyNameService } from '../../core/company-name.service';
import { TableSearchComponent, matchesSearch } from '../../shared/table-search.component';

const LIVE_SAMPLE_MS = 2000;

@Component({
  selector: 'app-portfolio-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    HeatmapComponent,
    LineChartComponent,
    KanbanBoardComponent,
    MoneyPipe,
    PctPipe,
    PricePipe,
    QtyPipe,
    PriceFlashDirective,
    TableSearchComponent,
  ],
  host: { class: 'grid gap-2 grid-cols-1 xl:grid-cols-2 auto-rows-min' },
  template: `
    <section class="panel flex h-[300px] flex-col overflow-hidden">
      <div class="panel-title">
        <span>Heatmap · weight × P&amp;L</span>
        <span class="num normal-case tracking-normal" [class]="signClass(portfolio.unrealizedPnl())">{{
          portfolio.unrealizedPnl() | money: true
        }}</span>
      </div>
      <app-heatmap class="m-2 flex-1" [positions]="portfolio.positions()" />
    </section>

    <section class="panel flex h-[300px] flex-col overflow-hidden">
      <div class="panel-title">
        <span>Portfolio value</span>
        <span class="num normal-case tracking-normal text-fg">{{ portfolio.total() | money }}</span>
      </div>
      <app-line-chart
        class="flex-1"
        data-testid="pnl-chart"
        [points]="pnlPoints()"
        [color]="'#ecad0a'"
        resetKey="pnl"
        emptyText="No snapshots yet"
      />
    </section>

    <section class="panel flex max-h-[420px] flex-col overflow-hidden">
      <div class="panel-title">
        <span>Positions</span>
        <span class="num normal-case tracking-normal">
          Cash {{ portfolio.cash() | money }} · Invested {{ portfolio.positionsValue() | money }}
        </span>
      </div>
      <app-table-search class="border-b border-edge p-2" testId="positions-search" [(query)]="query" />
      <div class="min-h-0 flex-1 overflow-auto">
        <table class="grid-table" data-testid="positions-table">
          <thead>
            <tr>
              <th>Ticker</th>
              <th>Name</th>
              <th class="!text-right">Qty</th>
              <th class="!text-right">Avg cost</th>
              <th class="!text-right">Price</th>
              <th class="!text-right">Value</th>
              <th class="!text-right">Unreal. P&amp;L</th>
              <th class="!text-right">%</th>
            </tr>
          </thead>
          <tbody>
            @for (p of positions(); track p.ticker) {
              <tr [attr.data-testid]="'positions-row-' + p.ticker" class="hover:bg-panel-2">
                <td class="font-semibold">{{ p.ticker }}</td>
                <td class="max-w-[12rem] truncate text-muted" [attr.title]="names.name(p.ticker)">{{ names.name(p.ticker) ?? '' }}</td>
                <td class="num text-right">{{ p.quantity | qty }}</td>
                <td class="num text-right">{{ p.avgCost | price }}</td>
                <td class="num text-right"><span class="rounded px-1" [appPriceFlash]="p.currentPrice">{{ p.currentPrice | price }}</span></td>
                <td class="num text-right">{{ p.marketValue | money }}</td>
                <td class="num text-right" [class]="signClass(p.unrealizedPnl)">{{ p.unrealizedPnl | money: true }}</td>
                <td class="num text-right" [class]="signClass(p.unrealizedPnlPercent)">{{ p.unrealizedPnlPercent | pct }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="8" class="py-6 text-center text-muted" data-testid="positions-empty">
                  {{ portfolio.positions().length ? 'No position matches "' + query() + '"' : 'No open positions' }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel flex min-h-[360px] flex-col overflow-hidden xl:row-span-2">
      <div class="panel-title">
        <span>Target prices · Support / Resistance</span>
        <span class="normal-case tracking-normal">Alerts via Camunda workflow</span>
      </div>
      <app-kanban-board class="min-h-0 flex-1" />
    </section>
  `,
})
export class PortfolioPage {
  readonly portfolio = inject(PortfolioService);
  readonly signClass = signClass;
  readonly names = inject(CompanyNameService);

  /** Full-text search on ticker and company name. */
  readonly query = signal('');
  readonly positions = computed(() =>
    this.portfolio.positions().filter((p) => matchesSearch(this.query(), p.ticker, this.names.name(p.ticker))),
  );

  /** Live total sampled every couple of seconds (appended to the snapshot history). */
  private readonly liveSample = signal<PricePoint | null>(null);

  readonly pnlPoints = computed<PricePoint[]>(() => {
    const pts = this.portfolio.history().map((s) => ({ time: Date.parse(s.recordedAt), value: s.totalValue }));
    const live = this.liveSample();
    if (live && (!pts.length || live.time > pts[pts.length - 1].time)) pts.push(live);
    return pts;
  });

  constructor() {
    this.portfolio.loadHistory();
    const sample = () => {
      const total = this.portfolio.total();
      if (total != null) this.liveSample.set({ time: Date.now(), value: total });
    };
    sample();
    const timer = setInterval(sample, LIVE_SAMPLE_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
