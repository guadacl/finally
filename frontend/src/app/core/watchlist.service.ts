import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { WatchlistItem } from '../api/models';
import { PriceStreamService } from './price-stream.service';

/** Change % of `price` vs `base` (null when not computable). */
export function changePercent(price: number | null | undefined, base: number | null | undefined): number | null {
  if (price == null || base == null || base === 0) return null;
  return ((price - base) / base) * 100;
}

export function normalizeTicker(raw: string): string {
  return raw.trim().toUpperCase();
}

export const TICKER_PATTERN = /^[A-Z][A-Z0-9.\-]{0,9}$/;

@Injectable({ providedIn: 'root' })
export class WatchlistService {
  private readonly api = inject(ApiClient);
  private readonly prices = inject(PriceStreamService);

  readonly items = signal<WatchlistItem[]>([]);
  readonly loaded = signal(false);

  load(): void {
    this.api.getWatchlist().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  add(rawTicker: string): Observable<WatchlistItem> {
    const ticker = normalizeTicker(rawTicker);
    return this.api.addToWatchlist({ ticker }).pipe(
      tap((item) =>
        this.items.update((list) =>
          list.some((x) => x.ticker === item.ticker) ? list : [...list, item],
        ),
      ),
    );
  }

  remove(ticker: string): Observable<void> {
    return this.api.removeFromWatchlist(ticker).pipe(
      tap(() => this.items.update((list) => list.filter((x) => x.ticker !== ticker))),
    );
  }

  /** Base price for change %: server sessionOpenPrice, else first price seen by this client. */
  sessionOpen(item: WatchlistItem): number | null {
    return item.sessionOpenPrice ?? this.prices.state(item.ticker)()?.firstPrice ?? item.price ?? null;
  }
}
