import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { PriceAlert, PriceAlertCreate } from '../api/models';

export const MAX_ALERT_COMMENT = 500;

export type AlertStatus = 'above' | 'below' | 'inside' | 'unknown';

/** Where the live price sits relative to the alert's limits. */
export function alertStatus(price: number | null | undefined, lower?: number | null, upper?: number | null): AlertStatus {
  if (price == null) return 'unknown';
  if (upper != null && price >= upper) return 'above';
  if (lower != null && price <= lower) return 'below';
  return 'inside';
}

/**
 * Day range shown for an alert: the server's day high/low (from the market data source), widened by the live
 * price so a new high/low appears immediately instead of at the next refresh.
 */
export function liveDayRange(
  high: number | null | undefined,
  low: number | null | undefined,
  price: number | null | undefined,
): { high: number | null; low: number | null } {
  const usable = (vs: (number | null | undefined)[]) => vs.filter((v): v is number => v != null && Number.isFinite(v) && v > 0);
  const highs = usable([high, price]);
  const lows = usable([low, price]);
  return { high: highs.length ? Math.max(...highs) : null, low: lows.length ? Math.min(...lows) : null };
}

/** Parses an optional price field: '' → null, otherwise a positive number (or an error message). */
function parseLimit(raw: string, label: string): number | null | string {
  const text = (raw ?? '').trim().replace(',', '.');
  if (!text) return null;
  const n = Number(text);
  if (!Number.isFinite(n) || n <= 0) return `${label} must be a number greater than 0`;
  return n;
}

/** Client-side mirror of the server rules. Returns the request body or an error message. */
export function buildAlert(ticker: string, upperRaw: string, lowerRaw: string, commentRaw: string): PriceAlertCreate | string {
  if (!ticker) return 'Choose a stock from your watchlist';
  const upper = parseLimit(upperRaw, 'Upper limit');
  if (typeof upper === 'string') return upper;
  const lower = parseLimit(lowerRaw, 'Lower limit');
  if (typeof lower === 'string') return lower;
  if (upper == null && lower == null) return 'Set an upper limit, a lower limit, or both';
  if (upper != null && lower != null && lower >= upper) return 'The lower limit must be below the upper limit';
  const comment = (commentRaw ?? '').trim();
  if (comment.length > MAX_ALERT_COMMENT) return `The comment can be at most ${MAX_ALERT_COMMENT} characters`;
  return { ticker, upperLimit: upper, lowerLimit: lower, comment: comment || null };
}

@Injectable({ providedIn: 'root' })
export class AlertsService {
  private readonly api = inject(ApiClient);

  readonly alerts = signal<PriceAlert[]>([]);
  readonly loaded = signal(false);

  load(): void {
    this.api.getPriceAlerts().subscribe({
      next: (list) => {
        this.alerts.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  create(body: PriceAlertCreate): Observable<PriceAlert> {
    // one alert per ticker: the server returns the existing alert (updated) when the ticker already had one
    return this.api.createPriceAlert(body).pipe(
      tap((a) => this.alerts.update((list) => (list.some((x) => x.id === a.id) ? list.map((x) => (x.id === a.id ? a : x)) : [...list, a]))),
    );
  }

  remove(id: string): Observable<void> {
    return this.api.deletePriceAlert(id).pipe(tap(() => this.alerts.update((list) => list.filter((a) => a.id !== id))));
  }
}
