import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, of, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { KanbanCard, KanbanCardUpdate, KanbanColumn } from '../api/models';

export type PriceValidation = { ok: true; value: number } | { ok: false; error: string };

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
 * Hint (non-blocking): support targets normally sit below the current price,
 * resistance targets above it.
 */
export function targetHint(column: KanbanColumn, target: number, price: number | null | undefined): string | null {
  if (price == null) return null;
  if (column === 'SUPPORT' && target > price) return 'Support target is above the current price';
  if (column === 'RESISTANCE' && target < price) return 'Resistance target is below the current price';
  return null;
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

  load(): void {
    this.api.getKanbanCards().subscribe({
      next: (cards) => this.cards.set(cards),
      error: () => {
        /* keep current state */
      },
    });
  }

  create(ticker: string, column: KanbanColumn, targetPrice: number): Observable<KanbanCard> {
    return this.api
      .createKanbanCard({ ticker: ticker.trim().toUpperCase(), column, targetPrice })
      .pipe(tap((card) => this.upsert(card)));
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
    if (v.value === card.targetPrice) return v;
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
        next.push({ ...old, column, sortOrder: index, armed: moved ? true : old.armed });
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
