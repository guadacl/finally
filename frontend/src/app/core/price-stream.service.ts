import { Injectable, OnDestroy, Signal, WritableSignal, inject, signal } from '@angular/core';
import { SSE_PRICES_URL } from '../api/api-client';
import { Direction, PriceUpdate } from '../api/models';
import {
  ConnectionStatus,
  ES_CLOSED,
  ES_CONNECTING,
  EVENT_SOURCE_FACTORY,
  EventSourceLike,
} from './event-source';

export interface PricePoint {
  /** epoch milliseconds */
  time: number;
  value: number;
}

export interface TickerState {
  ticker: string;
  price: number;
  previousPrice: number;
  direction: Direction;
  timestamp: string;
  /** First price seen by this client (fallback base for change %). */
  firstPrice: number;
  /** Bounded price history accumulated since page load (oldest first). */
  history: PricePoint[];
}

export const MAX_HISTORY = 600;
const MANUAL_RETRY_MS = 3000;

/** Parse and validate one SSE `price` event payload. Returns null when malformed. */
export function parsePriceUpdate(data: string): PriceUpdate | null {
  try {
    const raw = JSON.parse(data) as Partial<PriceUpdate>;
    if (
      typeof raw.ticker !== 'string' ||
      typeof raw.price !== 'number' ||
      !Number.isFinite(raw.price)
    ) {
      return null;
    }
    const prev = typeof raw.previousPrice === 'number' ? raw.previousPrice : raw.price;
    const direction: Direction =
      raw.direction === 'up' || raw.direction === 'down' || raw.direction === 'flat'
        ? raw.direction
        : raw.price > prev
          ? 'up'
          : raw.price < prev
            ? 'down'
            : 'flat';
    return {
      ticker: raw.ticker.toUpperCase(),
      price: raw.price,
      previousPrice: prev,
      timestamp: typeof raw.timestamp === 'string' ? raw.timestamp : new Date().toISOString(),
      direction,
    };
  } catch {
    return null;
  }
}

/**
 * Single shared EventSource on `/api/stream/prices`.
 * Exposes one signal per ticker plus the connection status.
 */
@Injectable({ providedIn: 'root' })
export class PriceStreamService implements OnDestroy {
  private readonly factory = inject(EVENT_SOURCE_FACTORY);
  private source: EventSourceLike | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly states = new Map<string, WritableSignal<TickerState | null>>();

  private readonly _status = signal<ConnectionStatus>('disconnected');
  readonly status: Signal<ConnectionStatus> = this._status.asReadonly();

  /** Incremented on every accepted update (cheap global "something changed" signal). */
  private readonly _ticks = signal(0);
  readonly ticks: Signal<number> = this._ticks.asReadonly();

  connect(): void {
    if (this.source) return;
    this.clearRetry();
    const es = this.factory(SSE_PRICES_URL);
    this.source = es;
    // Yellow while (re)establishing the connection; onopen flips it to green.
    this._status.set('reconnecting');
    es.onopen = () => this._status.set('connected');
    es.onerror = () => {
      if (es.readyState === ES_CLOSED) {
        // Browser gave up (e.g. non-200 from a proxy) — retry ourselves.
        this._status.set('disconnected');
        es.close();
        if (this.source === es) this.source = null;
        this.retryTimer = setTimeout(() => this.connect(), MANUAL_RETRY_MS);
      } else if (es.readyState === ES_CONNECTING) {
        this._status.set('reconnecting');
      }
    };
    es.addEventListener('price', (ev: MessageEvent) => {
      const update = parsePriceUpdate(String(ev.data));
      if (update) this.apply(update);
    });
  }

  disconnect(): void {
    this.clearRetry();
    this.source?.close();
    this.source = null;
    this._status.set('disconnected');
  }

  ngOnDestroy(): void {
    this.disconnect();
  }

  /** Signal with the live state of a ticker (null until the first price arrives). */
  state(ticker: string): Signal<TickerState | null> {
    return this.slot(ticker.toUpperCase());
  }

  /** Latest price snapshot (reactive when read inside a computed/template). */
  price(ticker: string): number | null {
    return this.state(ticker)()?.price ?? null;
  }

  /** Apply one update (public for tests and for seeding from REST data). */
  apply(update: PriceUpdate): void {
    const slot = this.slot(update.ticker);
    const prev = slot();
    const point: PricePoint = { time: Date.parse(update.timestamp) || Date.now(), value: update.price };
    let history = prev ? prev.history : [];
    history = history.length >= MAX_HISTORY ? [...history.slice(history.length - MAX_HISTORY + 1), point] : [...history, point];
    slot.set({
      ticker: update.ticker,
      price: update.price,
      previousPrice: update.previousPrice,
      direction: update.direction,
      timestamp: update.timestamp,
      firstPrice: prev?.firstPrice ?? update.price,
      history,
    });
    this._ticks.update((n) => n + 1);
  }

  private slot(ticker: string): WritableSignal<TickerState | null> {
    let s = this.states.get(ticker);
    if (!s) {
      s = signal<TickerState | null>(null);
      this.states.set(ticker, s);
    }
    return s;
  }

  private clearRetry(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }
}
