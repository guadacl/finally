import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, of, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { KanbanCard, KanbanCardStatus, KanbanCardUpdate, KanbanColumn } from '../api/models';

/** Same limit as the backend (kanban_cards.comment). */
export const MAX_CARD_COMMENT = 500;

export type PriceValidation ={ ok: true; value: number } | { ok: false; error: string };

/** Validate a target price typed by the user: must be a finite number > 0. */
export function validateTargetPrice(raw: string | number | null | undefined): PriceValidation {
  if (raw === null || raw === undefined || String(raw).trim() === '') {
    return { ok: false, error: 'Price is required' };
  }
  const value = typeof raw === 'number' ? raw : Number(String(raw).replace(/[$,\s]/g, ''));
  if (!Number.isFinite(value)) return { ok: false, error: 'Not a number' };
  if (value <= 0) return { ok: false, error: 'Price must be > 0' };
  return { ok: true, value: Math.round(value * 10000) / 10000 };
}

/**
 * Target price as shown in an editable field: 2 decimals, or up to the 4 the backend stores when the target
 * has more (185.1234 stays 185.1234), so focusing and leaving the field never changes the target by rounding.
 */
export function formatTarget(target: number): string {
  const two = target.toFixed(2);
  return Number(two) === target ? two : String(Number(target.toFixed(4)));
}

/** ACTIVE / NOTIFIED of a card: one rule for the Portfolio board and the Alerts tables. */
export function cardStatus(card: KanbanCard): KanbanCardStatus {
  return card.status;
}

/**
 * Hint (non-blocking): support targets normally sit below the current price,
 * resistance targets above it.
 */
export function targetHint(column: KanbanColumn, target: number, price: number | null | undefined): string | null {
  if (price == null) return null;
  if (column === 'SUPPORT' && target > price) return 'Support is above the current price';
  if (column === 'RESISTANCE' && target < price) return 'Resistance is below the current price';
  return null;
}

/** DIST shown on a card: % from the current price to the target (negative = target below price). */
export function cardDistance(target: number, price: number | null | undefined): number | null {
  return price ? ((target - price) / price) * 100 : null;
}

/**
 * Column display order: |DIST| ascending, so the card closest to its target (to triggering) is on
 * top, whichever side of the price it sits. Cards without a price go last; ties keep the saved order.
 */
export function sortByDistance(cards: readonly KanbanCard[], priceOf: (card: KanbanCard) => number | null | undefined): KanbanCard[] {
  return cards
    .map((card, i) => {
      const d = cardDistance(card.targetPrice, priceOf(card));
      return { card, i, d: d == null ? null : Math.abs(d) };
    })
    .sort((a, b) => (a.d == null ? 1 : 0) - (b.d == null ? 1 : 0) || (a.d ?? 0) - (b.d ?? 0) || a.i - b.i)
    .map((x) => x.card);
}

/**
 * `sorted` re-ordered as last shown (`shownIds`), new cards appended: used while a card field is being edited,
 * because moving the edited row in the DOM would blur its input and save a half-typed value.
 */
export function keepShownOrder(shownIds: readonly string[], sorted: readonly KanbanCard[]): KanbanCard[] {
  const byId = new Map(sorted.map((c) => [c.id, c]));
  const shown = new Set(shownIds);
  return [
    ...shownIds.filter((id) => byId.has(id)).map((id) => byId.get(id)!),
    ...sorted.filter((c) => !shown.has(c.id)),
  ];
}

function sortCards(cards: KanbanCard[]): KanbanCard[] {
  return [...cards].sort((a, b) => a.sortOrder - b.sortOrder || a.createdAt.localeCompare(b.createdAt));
}

@Injectable({ providedIn: 'root' })
export class KanbanService {
  private readonly api = inject(ApiClient);

  readonly cards = signal<KanbanCard[]>([]);
  readonly support = computed(() => sortCards(this.cards().filter((c) => c.column === 'SUPPORT')));
  readonly resistance = computed(() => sortCards(this.cards().filter((c) => c.column === 'RESISTANCE')));

  /** When the cards were last loaded (epoch ms), 0 = never. */
  private loadedAt = 0;

  load(): void {
    this.api.getKanbanCards().subscribe({
      next: (cards) => {
        this.loadedAt = Date.now();
        this.cards.set(cards);
      },
      error: () => {
        /* keep current state */
      },
    });
  }

  /** Loads only when the last load is older than `maxAgeMs` (e.g. a page opened right after the app loaded them). */
  refresh(maxAgeMs: number): void {
    if (Date.now() - this.loadedAt >= maxAgeMs) this.load();
  }

  create(ticker: string, column: KanbanColumn, targetPrice: number, comment?: string | null): Observable<KanbanCard> {
    const note = (comment ?? '').trim();
    return this.api
      .createKanbanCard({ ticker: ticker.trim().toUpperCase(), column, targetPrice, ...(note ? { comment: note } : {}) })
      .pipe(tap((card) => this.upsert(card)));
  }

  /**
   * PATCHes the comment (trimmed; empty clears it). Returns an error string when too long and
   * null when unchanged, instead of a request.
   */
  updateComment(card: KanbanCard, raw: string): Observable<KanbanCard> | string | null {
    const note = (raw ?? '').trim();
    if (note.length > MAX_CARD_COMMENT) return `The comment can be at most ${MAX_CARD_COMMENT} characters`;
    if (note === (card.comment ?? '')) return null;
    return this.update(card.id, { comment: note });
  }

  update(id: string, patch: KanbanCardUpdate): Observable<KanbanCard> {
    return this.api.updateKanbanCard(id, patch).pipe(tap((card) => this.upsert(card)));
  }

  /**
   * Validates then PATCHes the target price. Returns the validation result instead of a request
   * when the input is invalid or unchanged.
   */
  updateTargetPrice(card: KanbanCard, raw: string | number): Observable<KanbanCard> | PriceValidation {
    const v = validateTargetPrice(raw);
    if (!v.ok) return v;
    // unchanged: same value, or exactly the text the field showed (never re-arms a NOTIFIED card by accident)
    if (v.value === card.targetPrice || String(raw).trim() === formatTarget(card.targetPrice)) {
      return { ok: true, value: card.targetPrice };
    }
    return this.update(card.id, { targetPrice: v.value });
  }

  delete(id: string): Observable<void> {
    return this.api.deleteKanbanCard(id).pipe(
      tap(() => this.cards.update((list) => list.filter((c) => c.id !== id))),
    );
  }

  /**
   * Apply a new ordering after a drag & drop. `columns` holds the full, ordered card lists of
   * both columns as they should look now. Updates local state optimistically and PATCHes every
   * card whose column or sortOrder changed (the moved card carries `column`).
   */
  reorder(columns: Record<KanbanColumn, KanbanCard[]>): Observable<unknown> {
    const before = new Map(this.cards().map((c) => [c.id, c]));
    const next: KanbanCard[] = [];
    const requests: Observable<KanbanCard>[] = [];
    (Object.keys(columns) as KanbanColumn[]).forEach((column) => {
      columns[column].forEach((card, index) => {
        const old = before.get(card.id) ?? card;
        const patch: KanbanCardUpdate = {};
        if (old.column !== column) patch.column = column;
        if (old.sortOrder !== index || patch.column) patch.sortOrder = index;
        const moved = patch.column !== undefined;
        next.push({ ...old, column, sortOrder: index, armed: moved ? true : old.armed, status: moved ? 'ACTIVE' : old.status });
        if (Object.keys(patch).length) requests.push(this.api.updateKanbanCard(card.id, patch));
      });
    });
    this.cards.set(next);
    if (!requests.length) return of([]);
    return forkJoin(requests).pipe(tap((updated) => updated.forEach((c) => this.upsert(c))));
  }

  private upsert(card: KanbanCard): void {
    this.cards.update((list) =>
      list.some((c) => c.id === card.id) ? list.map((c) => (c.id === card.id ? card : c)) : [...list, card],
    );
  }
}
