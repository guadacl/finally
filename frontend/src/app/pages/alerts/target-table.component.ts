import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { isObservable } from 'rxjs';
import { COLUMN_LABEL, KanbanCard, KanbanColumn, errorMessage } from '../../api/models';
import { liveDayRange } from '../../core/alerts.service';
import {
  KanbanService,
  MAX_CARD_COMMENT,
  cardDistance,
  cardStatus,
  formatTarget,
  keepShownOrder,
  sortByDistance,
  validateTargetPrice,
} from '../../core/kanban.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { TICKER_PATTERN, normalizeTicker } from '../../core/watchlist.service';
import { PctPipe, PricePipe, VolumePipe } from '../../shared/format';
import { PriceFlashDirective } from '../../shared/price-flash.directive';
import { fmtRsi, rsiClass, rsiTitle } from '../../shared/rsi';

/**
 * One Support or Resistance table on the Alerts page: the cards of one kanban column shown with the same market
 * columns as the Price Alerts table (price, day low / high, last-session volume, RSI), plus the inline-editable
 * target and comment, the distance to target and the alert status. Rows are ordered by |DIST| (nearest target first).
 */
@Component({
  selector: 'app-target-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe, PricePipe, PctPipe, VolumePipe, PriceFlashDirective],
  host: { class: 'block' },
  template: `
    <section class="panel flex h-full flex-col overflow-hidden" [attr.data-testid]="'target-table-' + column()">
      <div class="panel-title">
        <span class="flex items-center gap-2">
          <span class="h-1.5 w-1.5 rounded-full" [class]="isSupport() ? 'bg-down' : 'bg-up'"></span>
          <span [class]="isSupport() ? 'text-down' : 'text-up'">{{ label() }}</span>
          <span class="normal-case tracking-normal text-muted">
            · {{ isSupport() ? 'alerts when the price falls to the target' : 'alerts when the price rises to the target' }}
          </span>
        </span>
        <div class="flex items-center gap-3 normal-case tracking-normal">
          <span class="num">{{ rows().length }} {{ rows().length === 1 ? 'card' : 'cards' }} · notifies once · via Camunda</span>
          <button
            type="button"
            class="btn btn-submit flex h-7 w-7 items-center justify-center p-0 text-lg leading-none"
            [attr.data-testid]="'target-add-button-' + column()"
            [attr.title]="'Add a ' + label() + ' target'"
            [attr.aria-label]="'Add a ' + label() + ' target'"
            [attr.aria-expanded]="formOpen()"
            (click)="toggleForm()"
          >
            {{ formOpen() ? '×' : '+' }}
          </button>
        </div>
      </div>

      @if (formOpen()) {
        <form class="grid gap-3 border-b border-edge bg-panel-2 p-3 md:grid-cols-[9rem_9rem_1fr_auto]" (ngSubmit)="create()"
          [attr.data-testid]="'target-form-' + column()">
          <label class="flex flex-col gap-1">
            <span class="text-[10px] tracking-widest text-muted uppercase">Ticker</span>
            <input name="tTicker" class="field num uppercase" autocomplete="off" maxlength="10" placeholder="e.g. AAPL"
              [attr.data-testid]="'target-add-ticker-' + column()" [ngModel]="newTicker()" (ngModelChange)="newTicker.set($event); formError.set(null)" />
            <span class="num h-4 text-[11px] text-muted">@if (newLivePrice() != null) { now {{ newLivePrice() | price }} }</span>
          </label>
          <label class="flex flex-col gap-1">
            <span class="text-[10px] tracking-widest text-muted uppercase">Target price</span>
            <input name="tPrice" class="field num" inputmode="decimal" autocomplete="off" placeholder="e.g. 185.00"
              [attr.data-testid]="'target-add-price-' + column()" [ngModel]="newPrice()" (ngModelChange)="newPrice.set($event); formError.set(null)" />
          </label>
          <label class="flex flex-col gap-1">
            <span class="text-[10px] tracking-widest text-muted uppercase">Comment</span>
            <input name="tComment" class="field" autocomplete="off" placeholder="Why this target?" [attr.maxlength]="maxComment"
              [attr.data-testid]="'target-add-comment-' + column()" [ngModel]="newComment()" (ngModelChange)="newComment.set($event)" />
          </label>
          <div class="flex items-end gap-2">
            <button type="submit" class="btn btn-submit" [disabled]="busy()" [attr.data-testid]="'target-add-save-' + column()">Add</button>
            <button type="button" class="btn btn-ghost" (click)="closeForm()">Cancel</button>
          </div>
          @if (formError(); as e) {
            <p class="text-xs text-down md:col-span-4" [attr.data-testid]="'target-add-error-' + column()">{{ e }}</p>
          }
        </form>
      }

      <div class="min-h-0 flex-1 overflow-auto">
        @if (rows().length === 0) {
          <div class="flex h-full min-h-24 flex-col items-center justify-center gap-1 p-6 text-center text-sm text-muted"
            [attr.data-testid]="'target-empty-' + column()">
            <span>No {{ label() }} targets yet.</span>
            <span>Click <span class="font-bold text-fg">+</span> to add one, or add a price alert with a {{ isSupport() ? 'lower' : 'upper' }} limit.</span>
          </div>
        } @else {
          <table class="w-full text-xs">
            <thead class="sticky top-0 z-10 bg-panel text-[10px] tracking-widest text-muted uppercase">
              <tr class="border-b border-edge">
                <th class="px-3 py-2 text-left">Ticker</th>
                <th class="px-3 py-2 text-right">Price</th>
                <th class="px-3 py-2 text-right" title="Lowest price of the current trading day">Day low</th>
                <th class="px-3 py-2 text-right" title="Highest price of the current trading day">Day high</th>
                <th class="px-3 py-2 text-right" title="Shares traded in the last completed trading session">Vol (last session)</th>
                <th class="px-3 py-2 text-right" title="Relative Strength Index, 14 daily closes (live during the session): ≥ 70 overbought, ≤ 30 oversold">RSI (14)</th>
                <th class="px-3 py-2 text-right" title="Target price: Enter or leaving the field saves, Esc cancels">Target</th>
                <th class="px-3 py-2 text-right" title="Distance from the current price to the target">Dist</th>
                <th class="px-3 py-2 text-left" title="ACTIVE until the target is reached, then NOTIFIED (the alert is sent once)">Status</th>
                <th class="px-3 py-2 text-left">Comment</th>
                <th class="px-3 py-2 text-left">Added</th>
                <th class="w-14 px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              @for (c of rows(); track c.id) {
                <tr class="border-b border-edge/60 hover:bg-panel-2" [class.target-broken]="broken(c)"
                  [attr.data-testid]="'target-row-' + c.id" [attr.data-broken]="broken(c)" [attr.data-status]="statusOf(c)"
                  [attr.title]="broken(c) ? 'Price is above this resistance target' : null">
                  <td class="px-3 py-2 font-bold">{{ c.ticker }}</td>
                  <td class="num px-3 py-2 text-right"><span class="rounded px-1" [appPriceFlash]="livePrice(c)">{{ livePrice(c) | price }}</span></td>
                  <td class="num px-3 py-2 text-right text-muted">{{ dayRange(c).low | price }}</td>
                  <td class="num px-3 py-2 text-right text-muted">{{ dayRange(c).high | price }}</td>
                  <td class="num px-3 py-2 text-right text-muted"
                    [attr.title]="c.lastSessionDate ? 'Session of ' + (c.lastSessionDate | date: 'EEE d MMM') : 'Volume needs a Massive API key'">{{ c.lastSessionVolume | volume }}</td>
                  <td class="num px-3 py-2 text-right" [class]="rsiClass(c.rsi)" [attr.title]="rsiTitle(c.rsi)">{{ fmtRsi(c.rsi) }}</td>
                  <td class="px-3 py-1.5 text-right">
                    <input #t class="field num w-24 py-0.5 text-right" inputmode="decimal" [class]="isSupport() ? 'text-down' : 'text-up'"
                      [attr.data-testid]="'target-price-' + c.id" [value]="fmtTarget(c.targetPrice)"
                      (keydown.enter)="t.blur()" (keydown.escape)="t.value = fmtTarget(c.targetPrice); t.blur()" (focus)="editing.set(c.id)" (blur)="saveTarget(c, t); editing.set(null)" />
                  </td>
                  <td class="num px-3 py-2 text-right text-muted">{{ distance(c) | pct }}</td>
                  <td class="px-3 py-2">
                    <span class="rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wider uppercase"
                      [class]="statusOf(c) === 'NOTIFIED' ? 'border border-accent/60 bg-accent/10 text-accent' : 'bg-edge text-muted'"
                      [attr.data-testid]="'target-status-' + c.id"
                      [attr.title]="statusOf(c) === 'NOTIFIED' ? 'Target reached: notified once. Edit the target or move the card to re-arm.' : 'Notifies once when the target is reached'"
                    >{{ statusOf(c) === 'NOTIFIED' ? 'Notified' : 'Active' }}</span>
                  </td>
                  <td class="px-3 py-1.5">
                    <input #m class="field w-full min-w-40 py-0.5 text-[11px]" placeholder="Add a comment…" autocomplete="off"
                      [attr.maxlength]="maxComment" [attr.data-testid]="'target-comment-' + c.id" [value]="c.comment ?? ''" [attr.title]="c.comment ?? ''"
                      (keydown.enter)="m.blur()" (keydown.escape)="m.value = c.comment ?? ''; m.blur()" (focus)="editing.set(c.id)" (blur)="saveComment(c, m); editing.set(null)" />
                  </td>
                  <td class="num px-3 py-2 text-muted">{{ c.createdAt | date: 'dd MMM HH:mm' }}</td>
                  <td class="px-3 py-2 text-right whitespace-nowrap">
                    <button type="button" class="mr-2 text-muted hover:text-primary" [attr.data-testid]="'target-move-' + c.id"
                      [attr.title]="'Move to ' + otherLabel()" (click)="move(c)">⇅</button>
                    <button type="button" class="text-muted hover:text-down" [attr.data-testid]="'target-delete-' + c.id"
                      [attr.title]="'Delete ' + label() + ' target'" (click)="remove(c)">×</button>
                  </td>
                </tr>
                @if (rowError()[c.id]; as e) {
                  <tr><td colspan="12" class="px-3 pb-2 text-[11px] text-down" [attr.data-testid]="'target-error-' + c.id">{{ c.ticker }}: {{ e }}</td></tr>
                }
              }
            </tbody>
          </table>
        }
      </div>
    </section>
  `,
  styles: `
    /* Resistance target below the current price: highlighted in the accent (alert) yellow, as on the board. */
    tr.target-broken {
      background-color: rgba(236, 173, 10, 0.12);
      box-shadow: inset 3px 0 0 #ecad0a;
    }
  `,
})
export class TargetTableComponent {
  private readonly kanban = inject(KanbanService);
  private readonly prices = inject(PriceStreamService);

  readonly column = input.required<KanbanColumn>();

  readonly maxComment = MAX_CARD_COMMENT;
  readonly rsiClass = rsiClass;
  readonly rsiTitle = rsiTitle;
  readonly fmtRsi = fmtRsi;

  readonly isSupport = computed(() => this.column() === 'SUPPORT');
  readonly label = computed(() => COLUMN_LABEL[this.column()]);
  readonly otherColumn = computed<KanbanColumn>(() => (this.isSupport() ? 'RESISTANCE' : 'SUPPORT'));
  readonly otherLabel = computed(() => COLUMN_LABEL[this.otherColumn()]);

  /** Card whose target or comment field has focus; while set, the row order is frozen. */
  readonly editing = signal<string | null>(null);
  /** Row order last shown (card ids), kept while editing. */
  private shownOrder: string[] = [];

  /**
   * The column's cards, nearest target first; re-sorts as prices stream, except while a field is being edited:
   * moving the edited row would blur its input and save a half-typed value. New cards are appended meanwhile.
   */
  readonly rows = computed(() => {
    const cards = this.isSupport() ? this.kanban.support() : this.kanban.resistance();
    const sorted = sortByDistance(cards, (c) => this.livePrice(c));
    if (this.editing() != null) return keepShownOrder(this.shownOrder, sorted);
    this.shownOrder = sorted.map((c) => c.id);
    return sorted;
  });

  readonly formOpen = signal(false);
  readonly newTicker = signal('');
  readonly newPrice = signal('');
  readonly newComment = signal('');
  readonly busy = signal(false);
  readonly formError = signal<string | null>(null);
  readonly rowError = signal<Record<string, string>>({});

  readonly newLivePrice = computed(() => {
    const t = normalizeTicker(this.newTicker() ?? '');
    return t ? this.prices.price(t) : null;
  });

  livePrice(c: KanbanCard): number | null {
    return this.prices.state(c.ticker)()?.price ?? c.currentPrice ?? null;
  }

  dayRange(c: KanbanCard): { high: number | null; low: number | null } {
    return liveDayRange(c.dayHigh, c.dayLow, this.livePrice(c));
  }

  distance(c: KanbanCard): number | null {
    return cardDistance(c.targetPrice, this.livePrice(c));
  }

  readonly statusOf = cardStatus;
  readonly fmtTarget = formatTarget;

  /** Resistance target already below the current price. */
  broken(c: KanbanCard): boolean {
    const p = this.livePrice(c);
    return c.column === 'RESISTANCE' && p != null && c.targetPrice < p;
  }

  toggleForm(): void {
    if (this.formOpen()) this.closeForm();
    else this.formOpen.set(true);
  }

  closeForm(): void {
    this.formOpen.set(false);
    this.newTicker.set('');
    this.newPrice.set('');
    this.newComment.set('');
    this.formError.set(null);
  }

  create(): void {
    const ticker = normalizeTicker(this.newTicker() ?? '');
    if (!ticker || !TICKER_PATTERN.test(ticker)) {
      this.formError.set('Enter a valid ticker');
      return;
    }
    const v = validateTargetPrice(this.newPrice());
    if (!v.ok) {
      this.formError.set(v.error);
      return;
    }
    this.busy.set(true);
    this.kanban.create(ticker, this.column(), v.value, this.newComment()).subscribe({
      next: () => {
        this.busy.set(false);
        this.closeForm();
      },
      error: (err) => {
        this.busy.set(false);
        this.formError.set(errorMessage(err, 'Could not add the target'));
      },
    });
  }

  saveTarget(c: KanbanCard, el: HTMLInputElement): void {
    const result = this.kanban.updateTargetPrice(c, el.value);
    if (!isObservable(result)) {
      if (result.ok) {
        el.value = formatTarget(result.value);
        this.clearError(c.id);
      } else {
        this.setError(c.id, result.error);
      }
      return;
    }
    result.subscribe({
      next: (saved) => {
        el.value = formatTarget(saved.targetPrice);
        this.clearError(c.id);
      },
      error: (err) => this.setError(c.id, errorMessage(err, 'Update failed')),
    });
  }

  saveComment(c: KanbanCard, el: HTMLInputElement): void {
    const result = this.kanban.updateComment(c, el.value);
    if (result === null) {
      el.value = c.comment ?? '';
      return;
    }
    if (typeof result === 'string') {
      this.setError(c.id, result);
      return;
    }
    result.subscribe({
      next: (saved) => {
        el.value = saved.comment ?? '';
        this.clearError(c.id);
      },
      error: (err) => this.setError(c.id, errorMessage(err, 'Update failed')),
    });
  }

  /** Moves the card to the other table (re-arms it: status ACTIVE). */
  move(c: KanbanCard): void {
    this.kanban.update(c.id, { column: this.otherColumn() }).subscribe({
      error: (err) => {
        this.setError(c.id, errorMessage(err, 'Move failed'));
        this.kanban.load();
      },
    });
  }

  remove(c: KanbanCard): void {
    this.kanban.delete(c.id).subscribe({ error: (err) => this.setError(c.id, errorMessage(err, 'Delete failed')) });
  }

  private setError(id: string, message: string): void {
    this.rowError.update((m) => ({ ...m, [id]: message }));
  }

  private clearError(id: string): void {
    if (!this.rowError()[id]) return;
    this.rowError.update((m) => {
      const next = { ...m };
      delete next[id];
      return next;
    });
  }
}
