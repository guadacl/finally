import { Injectable, inject, signal } from '@angular/core';
import { ApiClient } from '../api/api-client';

/** Wait before asking again for a ticker whose name came back unknown (the backend may still be looking it up). */
export const NAME_RETRY_MS = 60_000;
const MAX_ATTEMPTS = 3;
const BATCH = 100;

/**
 * Company names per ticker, fetched lazily from `GET /api/reference/names` and shared by every table.
 * `name(ticker)` returns the name when known and otherwise queues the ticker; queued tickers are fetched
 * together in one request on the next macrotask, so a table asking for 20 names makes a single call.
 */
@Injectable({ providedIn: 'root' })
export class CompanyNameService {
  private readonly api = inject(ApiClient);

  /** ticker → name (null = asked, unknown). Reading it makes templates update when names arrive. */
  readonly names = signal<Record<string, string | null>>({});

  private readonly inFlight = new Set<string>();
  private readonly attempts = new Map<string, number>();
  private readonly retryAt = new Map<string, number>();
  private queue = new Set<string>();
  private timer: ReturnType<typeof setTimeout> | null = null;

  /** The company name, or null while unknown (and then queues a lookup). Safe to call from templates. */
  name(ticker: string): string | null {
    const t = ticker.trim().toUpperCase();
    const known = this.names()[t];
    if (known) return known;
    this.request(t);
    return null;
  }

  private request(t: string): void {
    if (!t || this.inFlight.has(t) || this.queue.has(t)) return;
    const tries = this.attempts.get(t) ?? 0;
    if (this.names()[t] || tries >= MAX_ATTEMPTS || Date.now() < (this.retryAt.get(t) ?? 0)) return;
    this.queue.add(t);
    this.timer ??= setTimeout(() => this.flush(), 0);
  }

  private flush(): void {
    this.timer = null;
    const tickers = [...this.queue];
    this.queue = new Set();
    for (let i = 0; i < tickers.length; i += BATCH) {
      this.fetch(tickers.slice(i, i + BATCH));
    }
  }

  private fetch(tickers: string[]): void {
    for (const t of tickers) {
      this.inFlight.add(t);
      this.attempts.set(t, (this.attempts.get(t) ?? 0) + 1);
    }
    const done = () => {
      const later = Date.now() + NAME_RETRY_MS;
      const unknown: string[] = [];
      for (const t of tickers) {
        this.inFlight.delete(t);
        if (!this.names()[t]) {
          this.retryAt.set(t, later);
          if ((this.attempts.get(t) ?? 0) < MAX_ATTEMPTS) unknown.push(t);
        }
      }
      // the backend looks up at most a few unknown names per call: ask again later for the rest
      if (unknown.length) {
        setTimeout(() => {
          for (const t of unknown) {
            this.retryAt.delete(t);
            this.request(t);
          }
        }, NAME_RETRY_MS);
      }
    };
    this.api.getCompanyNames(tickers).subscribe({
      next: (rows) => {
        this.names.update((m) => {
          const next = { ...m };
          for (const t of tickers) next[t] ??= null;
          for (const r of rows) next[r.ticker.toUpperCase()] = r.name ?? null;
          return next;
        });
        done();
      },
      error: done,
    });
  }
}
