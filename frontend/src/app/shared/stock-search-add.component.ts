import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, of, switchMap, map } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { TickerSearchResult, errorMessage } from '../api/models';
import { TICKER_PATTERN, WatchlistService, normalizeTicker } from '../core/watchlist.service';

/** Typing pause before the search runs (the free Massive plan allows only a few calls per minute). */
export const SEARCH_DEBOUNCE_MS = 400;
/** Characters needed before searching while typing (Enter searches any length). */
export const MIN_TYPEAHEAD = 2;

type Outcome = { query: string; results: TickerSearchResult[] } | { query: string; error: string };

/**
 * Watchlist "add" box with full-text search by company name or ticker (Massive / Polygon.io MCP server via
 * GET /api/tickers/search). One match + Enter adds it; several matches open a list to pick from. When the search
 * finds nothing or is unavailable, a valid ticker symbol is added as typed.
 */
@Component({
  selector: 'app-stock-search-add',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'relative block' },
  template: `
    <form class="flex gap-2 p-2" (submit)="$event.preventDefault(); submit()" autocomplete="off">
      <input
        name="watchSearch"
        data-testid="watchlist-add-input"
        class="field min-w-0 flex-1"
        placeholder="Search stock by name or ticker…"
        autocomplete="off"
        maxlength="50"
        role="combobox"
        aria-autocomplete="list"
        [attr.aria-expanded]="open()"
        aria-controls="watchlist-suggestions"
        [value]="query()"
        (input)="onInput($any($event.target).value)"
        (keydown.arrowdown)="move(1, $event)"
        (keydown.arrowup)="move(-1, $event)"
        (keydown.escape)="close()"
        (blur)="closeSoon()"
        (focus)="reopen()"
      />
      <button type="submit" data-testid="watchlist-add-button" class="btn btn-submit" [disabled]="busy() || !query().trim()">
        Add
      </button>
    </form>

    @if (open()) {
      <ul
        id="watchlist-suggestions"
        role="listbox"
        data-testid="watchlist-suggestions"
        class="absolute top-full right-2 left-2 z-30 -mt-1 max-h-72 overflow-auto rounded border border-edge bg-panel-2 py-1 text-xs shadow-lg"
      >
        @if (results().length > 1) {
          <li class="px-3 py-1 text-[10px] tracking-widest text-muted uppercase" data-testid="watchlist-pick-hint">
            {{ results().length }} stocks found · pick one
          </li>
        }
        @for (r of results(); track r.ticker; let i = $index) {
          <li
            role="option"
            [attr.aria-selected]="i === active()"
            [attr.aria-disabled]="isWatched(r.ticker)"
            [attr.data-testid]="'watchlist-suggestion-' + r.ticker"
            class="flex cursor-pointer items-center gap-3 px-3 py-1.5"
            [class.bg-primary/15]="i === active()"
            [class.opacity-50]="isWatched(r.ticker)"
            [class.cursor-default]="isWatched(r.ticker)"
            (mousedown)="$event.preventDefault()"
            (mouseenter)="active.set(i)"
            (click)="choose(r)"
          >
            <span class="num w-16 shrink-0 font-bold">{{ r.ticker }}</span>
            <span class="min-w-0 flex-1 truncate" [attr.title]="r.name">{{ r.name }}</span>
            @if (isWatched(r.ticker)) {
              <span class="shrink-0 text-[10px] text-muted">in watchlist</span>
            } @else if (r.exchange) {
              <span class="shrink-0 text-[10px] text-muted">{{ r.exchange }}{{ r.type && r.type !== 'CS' ? ' · ' + r.type : '' }}</span>
            }
          </li>
        }
      </ul>
    }

    @if (searching()) {
      <div class="px-2 pb-1 text-[11px] text-muted" data-testid="watchlist-searching">Searching…</div>
    } @else if (info(); as m) {
      <div class="px-2 pb-1 text-[11px] text-muted" data-testid="watchlist-search-info">{{ m }}</div>
    }
    @if (error(); as e) {
      <div data-testid="watchlist-error" class="border-t border-edge bg-down/10 px-2 py-1 text-xs text-down">{{ e }}</div>
    }
  `,
})
export class StockSearchAddComponent {
  private readonly api = inject(ApiClient);
  private readonly watchlist = inject(WatchlistService);

  readonly query = signal('');
  /** Results of the last finished search and the query they belong to. */
  readonly results = signal<TickerSearchResult[]>([]);
  private readonly resultsFor = signal<string | null>(null);
  private readonly searchError = signal<string | null>(null);
  readonly open = signal(false);
  readonly active = signal(-1);
  readonly searching = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly info = signal<string | null>(null);

  private readonly watched = computed(() => new Set(this.watchlist.items().map((i) => i.ticker)));
  private readonly typed = new Subject<string>();

  constructor() {
    this.typed
      .pipe(
        debounceTime(SEARCH_DEBOUNCE_MS),
        switchMap((q) => (q.length < MIN_TYPEAHEAD ? of(null) : this.lookup(q))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((outcome) => {
        if (outcome && outcome.query === this.query().trim()) {
          this.apply(outcome);
          this.open.set('results' in outcome && outcome.results.length > 0);
        }
      });
  }

  isWatched(ticker: string): boolean {
    return this.watched().has(ticker);
  }

  onInput(value: string): void {
    this.query.set(value);
    this.error.set(null);
    this.info.set(null);
    const q = value.trim();
    if (q.length < MIN_TYPEAHEAD) {
      this.open.set(false);
      this.results.set([]);
      this.resultsFor.set(null);
      this.searching.set(false);
    } else {
      this.searching.set(true);
    }
    this.typed.next(q);
  }

  /** Enter / Add: the highlighted suggestion, else the only match, else the list to pick from. */
  submit(): void {
    const q = this.query().trim();
    if (!q || this.busy()) return;
    const highlighted = this.open() ? this.results()[this.active()] : undefined;
    if (highlighted) {
      this.choose(highlighted);
      return;
    }
    if (this.resultsFor() === q) {
      this.decide(q);
      return;
    }
    this.searching.set(true);
    this.lookup(q).subscribe((outcome) => {
      if (outcome.query !== this.query().trim()) return;
      this.apply(outcome);
      this.decide(q);
    });
  }

  choose(r: TickerSearchResult): void {
    if (this.isWatched(r.ticker)) {
      this.error.set(`${r.ticker} is already in your watchlist`);
      return;
    }
    this.addTicker(r.ticker);
  }

  move(step: number, event: Event): void {
    if (!this.results().length) return;
    event.preventDefault();
    this.open.set(true);
    const n = this.results().length;
    this.active.update((i) => (i < 0 ? (step > 0 ? 0 : n - 1) : (i + step + n) % n));
  }

  close(): void {
    this.open.set(false);
    this.active.set(-1);
  }

  /** On blur, after a click on a suggestion had its chance to run. */
  closeSoon(): void {
    setTimeout(() => this.close(), 150);
  }

  reopen(): void {
    if (this.resultsFor() === this.query().trim() && this.results().length > 1) this.open.set(true);
  }

  private lookup(q: string) {
    return this.api.searchTickers(q).pipe(
      map((results): Outcome => ({ query: q, results })),
      catchError((err) => of<Outcome>({ query: q, error: errorMessage(err, 'Stock search failed') })),
    );
  }

  private apply(outcome: Outcome): void {
    this.searching.set(false);
    this.resultsFor.set(outcome.query);
    if ('error' in outcome) {
      this.results.set([]);
      this.searchError.set(outcome.error);
    } else {
      this.results.set(outcome.results);
      this.searchError.set(null);
    }
    this.active.set(-1);
  }

  /** After a search for q: add the single match, ask to pick among several, or fall back to the typed ticker. */
  private decide(q: string): void {
    const selectable = this.results().filter((r) => !this.isWatched(r.ticker));
    const typed = normalizeTicker(q);
    const exact = selectable.find((r) => r.ticker === typed);
    if (selectable.length === 1) {
      this.addTicker(selectable[0].ticker);
    } else if (selectable.length > 1) {
      this.open.set(true);
      this.active.set(exact ? this.results().indexOf(exact) : 0);
      this.info.set(`${selectable.length} stocks match "${q}": pick the one to add.`);
    } else if (this.results().length > 0) {
      this.error.set(
        this.results().some((r) => r.ticker === typed)
          ? `${typed} is already in your watchlist`
          : `Every stock matching "${q}" is already in your watchlist`,
      );
    } else if (TICKER_PATTERN.test(typed)) {
      // nothing found (or search unavailable): add the symbol as typed, the server validates it
      this.addTicker(typed);
    } else {
      this.error.set(this.searchError() ?? `No stock found for "${q}"`);
    }
  }

  private addTicker(ticker: string): void {
    this.close();
    this.busy.set(true);
    this.watchlist.add(ticker).subscribe({
      next: () => {
        this.busy.set(false);
        this.query.set('');
        this.results.set([]);
        this.resultsFor.set(null);
        this.error.set(null);
        this.info.set(null);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, `Could not add ${ticker}`));
      },
    });
  }
}
