import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WatchlistItem, errorMessage } from '../api/models';
import { PriceStreamService } from '../core/price-stream.service';
import { TICKER_PATTERN, WatchlistService, changePercent, normalizeTicker } from '../core/watchlist.service';
import { PctPipe, PricePipe, signClass } from './format';
import { PriceFlashDirective } from './price-flash.directive';
import { SparklineComponent } from './sparkline.component';
import { CompanyNameService } from '../core/company-name.service';
import { TableSearchComponent, matchesSearch } from './table-search.component';

/** One watchlist row; reads its ticker's live price signal. */
@Component({
  selector: 'tr[app-watchlist-row]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PricePipe, PctPipe, PriceFlashDirective, SparklineComponent],
  host: {
    '[attr.data-testid]': "'watchlist-row-' + item().ticker",
    '[class]': 'rowClass()',
    '(click)': 'selectable() && pick.emit(item().ticker)',
  },
  template: `
    <td class="w-1 pr-0">
      <span class="block h-5 w-0.5 rounded" [class.bg-accent]="selected()"></span>
    </td>
    <td class="font-semibold tracking-wide">{{ item().ticker }}</td>
    <td class="max-w-[11rem] truncate text-muted" [attr.title]="name()" data-testid="watchlist-name">{{ name() ?? '' }}</td>
    <td class="num text-right">
      <span class="rounded px-1" [appPriceFlash]="price()" data-testid="watchlist-price">{{ price() | price }}</span>
    </td>
    <td class="num text-right" [class]="changeClass()">{{ change() | pct }}</td>
    <td class="w-[100px]"><app-sparkline [history]="history()" [width]="96" [height]="22" /></td>
    @if (removable()) {
      <td class="w-6 text-right">
        <button
          type="button"
          class="cursor-pointer px-1 text-base leading-none text-muted hover:text-down"
          [attr.data-testid]="'watchlist-remove-' + item().ticker"
          [attr.aria-label]="'Remove ' + item().ticker"
          (click)="$event.stopPropagation(); remove.emit(item().ticker)"
        >
          &times;
        </button>
      </td>
    }
  `,
})
export class WatchlistRowComponent {
  private readonly prices = inject(PriceStreamService);
  private readonly watchlist = inject(WatchlistService);
  private readonly names = inject(CompanyNameService);

  readonly item = input.required<WatchlistItem>();
  readonly selected = input(false);
  readonly selectable = input(false);
  readonly removable = input(false);
  readonly pick = output<string>();
  readonly remove = output<string>();

  readonly name = computed(() => this.names.name(this.item().ticker));
  private readonly state = computed(() => this.prices.state(this.item().ticker)());
  readonly price = computed(() => this.state()?.price ?? this.item().price ?? null);
  readonly history = computed(() => this.state()?.history ?? []);
  readonly change = computed(() => {
    const s = this.state();
    if (!s) return this.item().changePercent ?? null;
    return changePercent(s.price, this.watchlist.sessionOpen(this.item()));
  });
  readonly changeClass = computed(() => signClass(this.change()));
  readonly rowClass = computed(
    () =>
      'transition-colors hover:bg-panel-2 ' +
      (this.selected() ? 'bg-primary/10 ' : '') +
      (this.selectable() ? 'cursor-pointer' : ''),
  );
}

/** Watchlist grid with optional add form, selection and remove buttons. */
@Component({
  selector: 'app-watchlist-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, WatchlistRowComponent, TableSearchComponent],
  host: { class: 'flex min-h-0 flex-col' },
  template: `
    @if (showAdd()) {
      <form class="flex gap-2 border-b border-edge p-2" (ngSubmit)="add()">
        <input
          name="watchTicker"
          data-testid="watchlist-add-input"
          class="field num w-28 min-w-0 flex-1 uppercase"
          placeholder="Add ticker"
          autocomplete="off"
          maxlength="10"
          [ngModel]="draft()"
          (ngModelChange)="draft.set($event); error.set(null)"
        />
        <button type="submit" data-testid="watchlist-add-button" class="btn btn-submit" [disabled]="busy() || !draft().trim()">
          Add
        </button>
      </form>
      @if (error(); as e) {
        <div data-testid="watchlist-error" class="border-b border-edge bg-down/10 px-2 py-1 text-xs text-down">{{ e }}</div>
      }
    }
    <app-table-search class="border-b border-edge p-2" testId="watchlist-search" [(query)]="query" />
    <div class="min-h-0 flex-1 overflow-auto">
      <table class="grid-table">
        <thead>
          <tr>
            <th></th>
            <th>Ticker</th>
            <th>Name</th>
            <th class="!text-right">Price</th>
            <th class="!text-right">Chg %</th>
            <th>Trend</th>
            @if (removable()) {
              <th></th>
            }
          </tr>
        </thead>
        <tbody>
          @for (item of rows(); track item.ticker) {
            <tr
              app-watchlist-row
              [item]="item"
              [selected]="item.ticker === selected()"
              [selectable]="selectable()"
              [removable]="removable()"
              (pick)="pick.emit($event)"
              (remove)="removeTicker($event)"
            ></tr>
          } @empty {
            <tr>
              <td colspan="7" class="py-6 text-center text-muted" data-testid="watchlist-empty">
                {{ !watchlist.loaded() ? 'Loading…' : watchlist.items().length ? 'No stock matches "' + query() + '"' : 'Watchlist is empty' }}
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class WatchlistTableComponent {
  readonly watchlist = inject(WatchlistService);

  readonly selected = input<string | null>(null);
  readonly selectable = input(false);
  readonly removable = input(true);
  readonly showAdd = input(true);
  readonly pick = output<string>();

  private readonly names = inject(CompanyNameService);
  /** Full-text search on ticker and company name. */
  readonly query = signal('');
  readonly rows = computed(() =>
    this.watchlist.items().filter((i) => matchesSearch(this.query(), i.ticker, this.names.name(i.ticker))),
  );

  readonly draft = signal('');
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  add(): void {
    const ticker = normalizeTicker(this.draft());
    if (!ticker) return;
    if (!TICKER_PATTERN.test(ticker)) {
      this.error.set(`"${ticker}" is not a valid ticker`);
      return;
    }
    this.busy.set(true);
    this.watchlist.add(ticker).subscribe({
      next: () => {
        this.busy.set(false);
        this.draft.set('');
        this.error.set(null);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, `Could not add ${ticker}`));
      },
    });
  }

  removeTicker(ticker: string): void {
    this.watchlist.remove(ticker).subscribe({
      error: (err) => this.error.set(errorMessage(err, `Could not remove ${ticker}`)),
    });
  }
}
