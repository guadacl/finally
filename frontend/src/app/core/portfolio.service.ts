import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { Portfolio, PortfolioSnapshot, Position, TradeRequest, TradeResult } from '../api/models';
import { PriceStreamService } from './price-stream.service';

/** Re-price positions with live prices (falls back to the server's currentPrice). */
export function livePositions(
  positions: readonly Position[],
  priceOf: (ticker: string) => number | null,
): Position[] {
  return positions.map((p) => {
    const price = priceOf(p.ticker) ?? p.currentPrice;
    const marketValue = p.quantity * price;
    const cost = p.quantity * p.avgCost;
    const unrealizedPnl = marketValue - cost;
    const unrealizedPnlPercent = cost !== 0 ? (unrealizedPnl / cost) * 100 : 0;
    return { ...p, currentPrice: price, marketValue, unrealizedPnl, unrealizedPnlPercent };
  });
}

/** cash + Σ qty × price */
export function totalValue(cash: number, positions: readonly Position[]): number {
  return positions.reduce((sum, p) => sum + p.quantity * p.currentPrice, cash);
}

@Injectable({ providedIn: 'root' })
export class PortfolioService {
  private readonly api = inject(ApiClient);
  private readonly prices = inject(PriceStreamService);

  readonly portfolio = signal<Portfolio | null>(null);
  readonly history = signal<PortfolioSnapshot[]>([]);
  readonly loadError = signal<string | null>(null);

  readonly cash = computed(() => this.portfolio()?.cashBalance ?? null);

  readonly positions = computed<Position[]>(() => {
    const p = this.portfolio();
    if (!p) return [];
    return livePositions(p.positions, (t) => this.prices.price(t));
  });

  readonly positionsValue = computed(() =>
    this.positions().reduce((s, p) => s + p.marketValue, 0),
  );

  readonly unrealizedPnl = computed(() =>
    this.positions().reduce((s, p) => s + p.unrealizedPnl, 0),
  );

  /** Live total value = cash + Σ qty × live price. */
  readonly total = computed(() => {
    const p = this.portfolio();
    if (!p) return null;
    return totalValue(p.cashBalance, this.positions());
  });

  load(): void {
    this.api.getPortfolio().subscribe({
      next: (p) => {
        this.portfolio.set(p);
        this.loadError.set(null);
      },
      error: () => this.loadError.set('Could not load portfolio'),
    });
  }

  loadHistory(): void {
    this.api.getPortfolioHistory().subscribe({
      next: (h) => this.history.set(h),
      error: () => {
        /* keep previous history */
      },
    });
  }

  trade(req: TradeRequest): Observable<TradeResult> {
    return this.api.executeTrade(req).pipe(
      tap((res) => {
        this.portfolio.set(res.portfolio);
        this.loadHistory();
      }),
    );
  }
}
