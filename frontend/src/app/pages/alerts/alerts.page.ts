import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { COLUMN_LABEL, KanbanColumn, PriceAlert, errorMessage } from '../../api/models';
import { KanbanService } from '../../core/kanban.service';
import { ToastService } from '../../core/toast.service';
import { AlertStatus, AlertsService, MAX_ALERT_COMMENT, alertStatus, buildAlert, liveDayRange } from '../../core/alerts.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { WatchlistService } from '../../core/watchlist.service';
import { PricePipe, VolumePipe } from '../../shared/format';
import { PriceFlashDirective } from '../../shared/price-flash.directive';
import { KanbanBoardComponent } from '../portfolio/kanban-board.component';

/** How often the list is reloaded for fresh day high/low, volume and RSI. */
export const REFRESH_MS = 30_000;

export type RsiZone = 'overbought' | 'oversold' | 'neutral' | 'unknown';

/** Classic RSI thresholds: ≥ 70 overbought, ≤ 30 oversold. */
export function rsiZone(rsi: number | null | undefined): RsiZone {
  if (rsi == null || !Number.isFinite(rsi)) return 'unknown';
  return rsi >= 70 ? 'overbought' : rsi <= 30 ? 'oversold' : 'neutral';
}

const STATUS: Record<AlertStatus, { label: string; cls: string }> = {
  above: { label: 'Above upper', cls: 'bg-up/15 text-up' },
  below: { label: 'Below lower', cls: 'bg-down/15 text-down' },
  inside: { label: 'In range', cls: 'bg-edge text-muted' },
  unknown: { label: 'No price', cls: 'bg-edge text-muted' },
};

@Component({
  selector: 'app-alerts-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, DatePipe, PricePipe, VolumePipe, PriceFlashDirective, KanbanBoardComponent],
  host: { class: 'block h-full' },
  template: `
    <div class="mx-auto flex h-full max-w-6xl flex-col gap-2">
    <section class="panel flex min-h-[240px] flex-1 flex-col overflow-hidden">
      <div class="panel-title">
        <span>Price alerts</span>
        <div class="flex items-center gap-3 normal-case tracking-normal">
          <span class="num">{{ alerts.alerts().length }} alerts · prices stream live</span>
          <button
            type="button"
            data-testid="alerts-add-button"
            class="btn btn-submit flex h-7 w-7 items-center justify-center p-0 text-lg leading-none"
            title="Add an alert"
            aria-label="Add an alert"
            [attr.aria-expanded]="formOpen()"
            (click)="toggleForm()"
          >
            {{ formOpen() ? '×' : '+' }}
          </button>
        </div>
      </div>

      @if (formOpen()) {
        <form class="grid gap-3 border-b border-edge bg-panel-2 p-3 md:grid-cols-[12rem_9rem_9rem_1fr_auto]" (ngSubmit)="save()" data-testid="alerts-form">
          @if (watchlistTickers().length === 0) {
            <p class="text-sm text-muted md:col-span-5">
              Your watchlist is empty. <a routerLink="/watchlists" class="text-primary underline">Add stocks to your watchlist</a> first.
            </p>
          } @else {
            <label class="flex flex-col gap-1">
              <span class="text-[10px] tracking-widest text-muted uppercase">Stock (from watchlist)</span>
              <select name="aTicker" data-testid="alerts-ticker" class="field" [ngModel]="ticker()" (ngModelChange)="ticker.set($event); error.set(null)">
                <option value="" disabled>Choose a stock…</option>
                @for (t of watchlistTickers(); track t) {
                  <option [value]="t">{{ t }}</option>
                }
              </select>
              <span class="num h-4 text-[11px] text-muted">
                @if (selectedPrice() != null) { now {{ selectedPrice() | price }} }
              </span>
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-[10px] tracking-widest text-muted uppercase">Upper limit</span>
              <input name="aUpper" data-testid="alerts-upper" class="field num" inputmode="decimal" autocomplete="off" placeholder="e.g. 250.00"
                [ngModel]="upper()" (ngModelChange)="upper.set($event); error.set(null)" />
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-[10px] tracking-widest text-muted uppercase">Lower limit</span>
              <input name="aLower" data-testid="alerts-lower" class="field num" inputmode="decimal" autocomplete="off" placeholder="e.g. 180.00"
                [ngModel]="lower()" (ngModelChange)="lower.set($event); error.set(null)" />
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-[10px] tracking-widest text-muted uppercase">Comment (on the cards)</span>
              <input name="aComment" data-testid="alerts-comment" class="field" autocomplete="off" placeholder="Saved on the Support / Resistance cards"
                [attr.maxlength]="maxComment" [ngModel]="comment()" (ngModelChange)="comment.set($event); error.set(null)" />
            </label>
            <div class="flex items-end gap-2">
              <button type="submit" data-testid="alerts-save" class="btn btn-submit" [disabled]="busy()">Add</button>
              <button type="button" data-testid="alerts-cancel" class="btn btn-ghost" (click)="closeForm()">Cancel</button>
            </div>
            @if (error(); as e) {
              <p data-testid="alerts-error" class="text-xs text-down md:col-span-5">{{ e }}</p>
            }
          }
        </form>
      }

      <div class="min-h-0 flex-1 overflow-auto">
        @if (alerts.alerts().length === 0) {
          <div class="flex h-full flex-col items-center justify-center gap-2 p-8 text-center text-sm text-muted" data-testid="alerts-empty">
            <span>No alerts yet.</span>
            <span>Click <span class="font-bold text-fg">+</span> to watch a stock from your watchlist between a lower and an upper limit.</span>
          </div>
        } @else {
          <table class="w-full text-xs">
            <thead class="sticky top-0 bg-panel text-[10px] tracking-widest text-muted uppercase">
              <tr class="border-b border-edge">
                <th class="px-3 py-2 text-left">Ticker</th>
                <th class="px-3 py-2 text-right">Price</th>
                <th class="px-3 py-2 text-right" title="Lowest price of the current trading day">Day low</th>
                <th class="px-3 py-2 text-right" title="Highest price of the current trading day">Day high</th>
                <th class="px-3 py-2 text-right" title="Shares traded in the last completed trading session">Vol (last session)</th>
                <th class="px-3 py-2 text-right" title="Relative Strength Index, 14 daily closes (live during the session): ≥ 70 overbought, ≤ 30 oversold">RSI (14)</th>
                <th class="px-3 py-2 text-right">Lower limit</th>
                <th class="px-3 py-2 text-right">Upper limit</th>
                <th class="px-3 py-2 text-left">Status</th>
                <th class="px-3 py-2 text-left">Added</th>
                <th class="w-8 px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              @for (a of alerts.alerts(); track a.id) {
                <tr class="border-b border-edge/60 hover:bg-panel-2" [attr.data-testid]="'alerts-row-' + a.id" [attr.data-status]="status(a)">
                  <td class="px-3 py-2 font-bold">{{ a.ticker }}</td>
                  <td class="num px-3 py-2 text-right"><span class="rounded px-1" [appPriceFlash]="livePrice(a)">{{ livePrice(a) | price }}</span></td>
                  <td class="num px-3 py-2 text-right text-muted" [attr.data-testid]="'alerts-daylow-' + a.id">{{ dayRange(a).low | price }}</td>
                  <td class="num px-3 py-2 text-right text-muted" [attr.data-testid]="'alerts-dayhigh-' + a.id">{{ dayRange(a).high | price }}</td>
                  <td class="num px-3 py-2 text-right text-muted" [attr.data-testid]="'alerts-volume-' + a.id"
                    [attr.title]="a.lastSessionDate ? 'Session of ' + (a.lastSessionDate | date: 'EEE d MMM') : 'Volume needs a Massive API key'">{{ a.lastSessionVolume | volume }}</td>
                  <td class="num px-3 py-2 text-right" [class]="rsiClass(a.rsi)" [attr.data-testid]="'alerts-rsi-' + a.id"
                    [attr.title]="rsiTitle(a.rsi)">{{ a.rsi != null ? a.rsi.toFixed(1) : '—' }}</td>
                  <td class="num px-3 py-2 text-right text-down">{{ a.lowerLimit != null ? (a.lowerLimit | price) : '—' }}</td>
                  <td class="num px-3 py-2 text-right text-up">{{ a.upperLimit != null ? (a.upperLimit | price) : '—' }}</td>
                  <td class="px-3 py-2">
                    <span class="rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wider uppercase" [class]="statusClass(a)">{{ statusLabel(a) }}</span>
                  </td>
                  <td class="num px-3 py-2 text-muted">{{ a.createdAt | date: 'dd MMM HH:mm' }}</td>
                  <td class="px-3 py-2 text-right">
                    <button type="button" class="text-muted hover:text-down" [attr.data-testid]="'alerts-delete-' + a.id" title="Delete alert" (click)="remove(a)">×</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    </section>

    <section class="panel flex min-h-[340px] flex-1 flex-col overflow-hidden" data-testid="alerts-kanban">
      <div class="panel-title">
        <span>Target prices · Support / Resistance</span>
        <span class="normal-case tracking-normal">Alerts via Camunda workflow</span>
      </div>
      <app-kanban-board class="min-h-0 flex-1" />
    </section>
    </div>
  `,
})
export class AlertsPage implements OnInit {
  readonly alerts = inject(AlertsService);
  private readonly watchlist = inject(WatchlistService);
  private readonly prices = inject(PriceStreamService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly kanban = inject(KanbanService);
  private readonly toasts = inject(ToastService);

  readonly maxComment = MAX_ALERT_COMMENT;
  readonly formOpen = signal(false);
  readonly ticker = signal('');
  readonly upper = signal('');
  readonly lower = signal('');
  readonly comment = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly watchlistTickers = computed(() => this.watchlist.items().map((i) => i.ticker));
  readonly selectedPrice = computed(() => (this.ticker() ? this.prices.price(this.ticker()) : null));

  ngOnInit(): void {
    this.alerts.load();
    // refresh day high/low and volume while the page is open (live prices come from the stream)
    const timer = setInterval(() => this.alerts.load(), REFRESH_MS);
    this.destroyRef.onDestroy(() => clearInterval(timer));
    if (this.watchlist.items().length === 0) {
      this.watchlist.load();
    }
  }

  toggleForm(): void {
    if (this.formOpen()) {
      this.closeForm();
    } else {
      this.formOpen.set(true);
    }
  }

  closeForm(): void {
    this.formOpen.set(false);
    this.ticker.set('');
    this.upper.set('');
    this.lower.set('');
    this.comment.set('');
    this.error.set(null);
  }

  save(): void {
    const body = buildAlert(this.ticker(), this.upper(), this.lower(), this.comment());
    if (typeof body === 'string') {
      this.error.set(body);
      return;
    }
    this.busy.set(true);
    this.alerts.create(body).subscribe({
      next: () => {
        this.busy.set(false);
        this.closeForm();
        this.addKanbanCards(body.ticker, body.lowerLimit, body.upperLimit, body.comment);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  /**
   * Mirror a new alert on the kanban board: the lower limit becomes a Support card, the upper
   * limit a Resistance card (only the limits that were set), both carrying the comment. The alert
   * is already saved, so a card failure is reported as a toast instead of blocking the form.
   */
  private addKanbanCards(ticker: string, lower: number | null | undefined, upper: number | null | undefined,
    comment: string | null | undefined): void {
    const cards: [KanbanColumn, number][] = [];
    if (lower != null) cards.push(['SUPPORT', lower]);
    if (upper != null) cards.push(['RESISTANCE', upper]);
    for (const [column, target] of cards) {
      this.kanban.create(ticker, column, target, comment).subscribe({
        error: (e) =>
          this.toasts.show({
            type: 'ERROR',
            ticker,
            column,
            targetPrice: target,
            message: `Alert saved, but the ${COLUMN_LABEL[column]} card could not be created: ${errorMessage(e)}`,
          }),
      });
    }
  }

  remove(a: PriceAlert): void {
    this.alerts.remove(a.id).subscribe({ error: () => this.alerts.load() });
  }

  rsiClass(rsi: number | null | undefined): string {
    return rsiZone(rsi) === 'overbought' ? 'text-down font-semibold' : rsiZone(rsi) === 'oversold' ? 'text-up font-semibold' : 'text-muted';
  }

  rsiTitle(rsi: number | null | undefined): string {
    const zone = rsiZone(rsi);
    if (zone === 'unknown') return 'RSI needs a Massive API key (daily closes); it appears shortly after the alert is added';
    return zone === 'overbought' ? 'Overbought (RSI ≥ 70)' : zone === 'oversold' ? 'Oversold (RSI ≤ 30)' : 'Neutral (30 < RSI < 70)';
  }

  livePrice(a: PriceAlert): number | null {
    return this.prices.price(a.ticker) ?? a.currentPrice ?? null;
  }

  dayRange(a: PriceAlert): { high: number | null; low: number | null } {
    return liveDayRange(a.dayHigh, a.dayLow, this.livePrice(a));
  }

  status(a: PriceAlert): AlertStatus {
    return alertStatus(this.livePrice(a), a.lowerLimit, a.upperLimit);
  }

  statusLabel(a: PriceAlert): string {
    return STATUS[this.status(a)].label;
  }

  statusClass(a: PriceAlert): string {
    return STATUS[this.status(a)].cls;
  }
}
