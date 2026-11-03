import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PriceAlert, errorMessage } from '../../api/models';
import {
  AlertSide, AlertStatus, AlertsService, MAX_ALERT_COMMENT, alertMargin, alertStatus, buildAlert, liveDayRange,
} from '../../core/alerts.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { WatchlistService } from '../../core/watchlist.service';
import { PctPipe, PricePipe, VolumePipe } from '../../shared/format';
import { PriceFlashDirective } from '../../shared/price-flash.directive';

/** How often the list is reloaded for fresh day high/low and volume. */
export const REFRESH_MS = 30_000;

const STATUS: Record<AlertStatus, { label: string; cls: string }> = {
  above: { label: 'Above resistance', cls: 'bg-up/15 text-up' },
  below: { label: 'Below support', cls: 'bg-down/15 text-down' },
  inside: { label: 'Not reached', cls: 'bg-edge text-muted' },
  unknown: { label: 'No price', cls: 'bg-edge text-muted' },
};

@Component({
  selector: 'app-alerts-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, DatePipe, NgTemplateOutlet, PctPipe, PricePipe, VolumePipe, PriceFlashDirective],
  host: { class: 'block h-full' },
  template: `
    <section class="panel mx-auto flex h-full max-w-6xl flex-col overflow-hidden">
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
              <span class="text-[10px] tracking-widest text-muted uppercase">Comment</span>
              <input name="aComment" data-testid="alerts-comment" class="field" autocomplete="off" placeholder="Why this alert?"
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
          <ng-container *ngTemplateOutlet="sideTable; context: { $implicit: 'support', rows: supportAlerts() }" />
          <ng-container *ngTemplateOutlet="sideTable; context: { $implicit: 'resistance', rows: resistanceAlerts() }" />
        }
      </div>
    </section>

    <ng-template #sideTable let-side let-rows="rows">
      <div class="border-b border-edge" [attr.data-testid]="'alerts-table-' + side">
        <div class="flex items-center justify-between bg-panel-2 px-3 py-1.5 text-[10px] font-semibold tracking-widest uppercase"
          [class.text-down]="side === 'support'" [class.text-up]="side === 'resistance'">
          <span>{{ side === 'support' ? 'Support (lower limit)' : 'Resistance (upper limit)' }}</span>
          <span class="num font-normal text-muted">{{ rows.length }}</span>
        </div>
        @if (rows.length === 0) {
          <p class="px-3 py-3 text-xs text-muted" [attr.data-testid]="'alerts-empty-' + side">
            No alerts with {{ side === 'support' ? 'a lower' : 'an upper' }} limit.
          </p>
        } @else {
          <table class="w-full text-xs">
            <thead class="bg-panel text-[10px] tracking-widest text-muted uppercase">
              <tr class="border-b border-edge">
                <th class="px-3 py-2 text-left">Ticker</th>
                <th class="px-3 py-2 text-right">Price</th>
                <th class="px-3 py-2 text-right" title="Lowest price of the current trading day">Day low</th>
                <th class="px-3 py-2 text-right" title="Highest price of the current trading day">Day high</th>
                <th class="px-3 py-2 text-right" title="Shares traded in the last completed trading session">Vol (last session)</th>
                <th class="px-3 py-2 text-right">{{ side === 'support' ? 'Support' : 'Resistance' }}</th>
                <th class="px-3 py-2 text-right" [attr.title]="marginHelp(side)">Margin</th>
                <th class="px-3 py-2 text-left">Status</th>
                <th class="px-3 py-2 text-left">Comment</th>
                <th class="px-3 py-2 text-left">Added</th>
                <th class="w-8 px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              @for (a of rows; track a.id) {
                <tr class="border-b border-edge/60 hover:bg-panel-2" [attr.data-testid]="'alerts-' + side + '-row-' + a.id" [attr.data-status]="status(a, side)">
                  <td class="px-3 py-2 font-bold">{{ a.ticker }}</td>
                  <td class="num px-3 py-2 text-right"><span class="rounded px-1" [appPriceFlash]="livePrice(a)">{{ livePrice(a) | price }}</span></td>
                  <td class="num px-3 py-2 text-right text-muted" [attr.data-testid]="'alerts-' + side + '-daylow-' + a.id">{{ dayRange(a).low | price }}</td>
                  <td class="num px-3 py-2 text-right text-muted" [attr.data-testid]="'alerts-' + side + '-dayhigh-' + a.id">{{ dayRange(a).high | price }}</td>
                  <td class="num px-3 py-2 text-right text-muted" [attr.data-testid]="'alerts-' + side + '-volume-' + a.id"
                    [attr.title]="a.lastSessionDate ? 'Session of ' + (a.lastSessionDate | date: 'EEE d MMM') : 'Volume needs a Massive API key'">{{ a.lastSessionVolume | volume }}</td>
                  <td class="num px-3 py-2 text-right" [class.text-down]="side === 'support'" [class.text-up]="side === 'resistance'">{{ level(a, side) | price }}</td>
                  <td class="num px-3 py-2 text-right" [class]="marginClass(a, side)" [attr.data-testid]="'alerts-' + side + '-margin-' + a.id">{{ margin(a, side) | pct }}</td>
                  <td class="px-3 py-2">
                    <span class="rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wider uppercase" [class]="statusClass(a, side)">{{ statusLabel(a, side) }}</span>
                  </td>
                  <td class="max-w-80 truncate px-3 py-2 text-muted" [attr.title]="a.comment ?? ''">{{ a.comment || '—' }}</td>
                  <td class="num px-3 py-2 text-muted">{{ a.createdAt | date: 'dd MMM HH:mm' }}</td>
                  <td class="px-3 py-2 text-right">
                    <button type="button" class="text-muted hover:text-down" [attr.data-testid]="'alerts-' + side + '-delete-' + a.id" title="Delete alert" (click)="remove(a)">×</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    </ng-template>
  `,
})
export class AlertsPage implements OnInit {
  readonly alerts = inject(AlertsService);
  private readonly watchlist = inject(WatchlistService);
  private readonly prices = inject(PriceStreamService);
  private readonly destroyRef = inject(DestroyRef);

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
  /** Alerts with a lower limit; one with both limits appears in both tables. */
  readonly supportAlerts = computed(() => this.alerts.alerts().filter((a) => a.lowerLimit != null));
  readonly resistanceAlerts = computed(() => this.alerts.alerts().filter((a) => a.upperLimit != null));

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
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  remove(a: PriceAlert): void {
    this.alerts.remove(a.id).subscribe({ error: () => this.alerts.load() });
  }

  livePrice(a: PriceAlert): number | null {
    return this.prices.price(a.ticker) ?? a.currentPrice ?? null;
  }

  dayRange(a: PriceAlert): { high: number | null; low: number | null } {
    return liveDayRange(a.dayHigh, a.dayLow, this.livePrice(a));
  }

  level(a: PriceAlert, side: AlertSide): number | null {
    return (side === 'support' ? a.lowerLimit : a.upperLimit) ?? null;
  }

  margin(a: PriceAlert, side: AlertSide): number | null {
    return alertMargin(this.livePrice(a), this.level(a, side), side);
  }

  /** Accent yellow once the level is reached or broken. */
  marginClass(a: PriceAlert, side: AlertSide): string {
    const m = this.margin(a, side);
    if (m == null) return 'text-muted';
    return m <= 0 ? 'font-semibold text-accent' : 'text-fg';
  }

  marginHelp(side: AlertSide): string {
    return side === 'support'
      ? 'How far the price can fall before reaching the support, in % of the price (negative = broken)'
      : 'How far the price can rise before reaching the resistance, in % of the price (negative = broken)';
  }

  /** Status against this table's level only; the other limit is ignored. */
  status(a: PriceAlert, side: AlertSide): AlertStatus {
    return side === 'support'
      ? alertStatus(this.livePrice(a), a.lowerLimit, null)
      : alertStatus(this.livePrice(a), null, a.upperLimit);
  }

  statusLabel(a: PriceAlert, side: AlertSide): string {
    return STATUS[this.status(a, side)].label;
  }

  statusClass(a: PriceAlert, side: AlertSide): string {
    return STATUS[this.status(a, side)].cls;
  }
}
