import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { isObservable } from 'rxjs';
import { KanbanCard, errorMessage } from '../../api/models';
import { KanbanService, MAX_CARD_COMMENT, cardDistance, targetHint } from '../../core/kanban.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { PctPipe, PricePipe } from '../../shared/format';
import { PriceFlashDirective } from '../../shared/price-flash.directive';

@Component({
  selector: 'app-kanban-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PricePipe, PctPipe, PriceFlashDirective],
  host: {
    '[attr.data-testid]': "'kanban-card-' + card().id",
    '[attr.data-armed]': 'card().armed',
    '[attr.data-broken]': 'broken()',
    '[attr.title]': "broken() ? 'Price is above this resistance target' : null",
    class: 'block rounded border border-edge bg-panel-2 px-2.5 py-2 shadow-sm shadow-black/20',
  },
  styles: `
    /* Resistance target below the current price: highlighted in the accent (alert) yellow. */
    :host([data-broken='true']) {
      border-color: #ecad0a;
      background-color: rgba(236, 173, 10, 0.12);
      box-shadow: 0 0 0 1px #ecad0a, 0 0 12px rgba(236, 173, 10, 0.35);
    }
  `,
  template: `
    <div class="flex items-center gap-2">
      <span class="cursor-grab text-muted select-none" title="Drag" aria-hidden="true">⋮⋮</span>
      <span class="text-sm font-bold tracking-wide">{{ card().ticker }}</span>
      <span
        class="ml-1"
        [class]="card().armed ? 'text-accent' : 'text-muted/50'"
        [attr.title]="card().armed ? 'Alert armed' : 'Alert fired — re-arms when price crosses back'"
        [attr.data-testid]="'kanban-card-armed-' + card().id"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm7-6V11a7 7 0 0 0-5.5-6.84V3.5a1.5 1.5 0 0 0-3 0v.66A7 7 0 0 0 5 11v5l-2 2v1h18v-1l-2-2Z" />
          @if (!card().armed) {
            <path d="M3 3l18 18" stroke="currentColor" stroke-width="2" />
          }
        </svg>
      </span>
      <button
        type="button"
        class="ml-auto cursor-pointer px-1 text-base leading-none text-muted hover:text-down"
        [attr.data-testid]="'kanban-card-delete-' + card().id"
        [attr.aria-label]="'Delete ' + card().ticker + ' card'"
        (click)="remove()"
        [disabled]="busy()"
      >
        &times;
      </button>
    </div>
    <div class="mt-1.5 flex items-center justify-between gap-2 text-xs">
      <div class="flex flex-col">
        <span class="text-[9px] tracking-widest text-muted uppercase">Price</span>
        <span class="num rounded px-0.5" [appPriceFlash]="livePrice()" [attr.data-testid]="'kanban-card-live-' + card().id">{{
          livePrice() | price
        }}</span>
      </div>
      <div class="flex flex-col items-center">
        <span class="text-[9px] tracking-widest text-muted uppercase">Dist</span>
        <span class="num text-muted">{{ distance() | pct }}</span>
      </div>
      <label class="flex flex-col items-end">
        <span class="text-[9px] tracking-widest text-muted uppercase">Target</span>
        <input
          #priceInput
          class="field num w-24 py-0.5 text-right"
          [style.border-color]="error() ? '#ef5350' : null"
          inputmode="decimal"
          [attr.data-testid]="'kanban-card-price-' + card().id"
          [value]="draft()"
          (input)="draft.set(priceInput.value); error.set(null)"
          (keydown.enter)="priceInput.blur()"
          (keydown.escape)="cancel(); priceInput.blur()"
          (blur)="commit()"
          (mousedown)="$event.stopPropagation()"
          (pointerdown)="$event.stopPropagation()"
        />
      </label>
    </div>
    <input
      #commentInput
      class="field mt-1.5 w-full py-0.5 text-[11px]"
      placeholder="Add a comment…"
      autocomplete="off"
      [attr.maxlength]="maxComment"
      [attr.data-testid]="'kanban-card-comment-' + card().id"
      [attr.title]="card().comment ?? ''"
      [value]="commentDraft()"
      (input)="commentDraft.set(commentInput.value); error.set(null)"
      (keydown.enter)="commentInput.blur()"
      (keydown.escape)="cancelComment(); commentInput.blur()"
      (blur)="commitComment()"
      (mousedown)="$event.stopPropagation()"
      (pointerdown)="$event.stopPropagation()"
    />
    @if (error(); as e) {
      <div class="mt-1 text-[10.5px] text-down" [attr.data-testid]="'kanban-card-error-' + card().id">{{ e }}</div>
    } @else if (hint(); as h) {
      <div class="mt-1 text-[10.5px] text-accent/80">{{ h }}</div>
    }
  `,
})
export class KanbanCardComponent {
  private readonly kanban = inject(KanbanService);
  private readonly prices = inject(PriceStreamService);

  readonly card = input.required<KanbanCard>();

  readonly draft = signal('');
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  readonly livePrice = computed(() => this.prices.state(this.card().ticker)()?.price ?? this.card().currentPrice ?? null);
  readonly distance = computed(() => cardDistance(this.card().targetPrice, this.livePrice()));
  readonly hint = computed(() => targetHint(this.card().column, this.card().targetPrice, this.livePrice()));
  /** Resistance card whose target is below the current price (price has broken through it). */
  readonly broken = computed(() => {
    const p = this.livePrice();
    return this.card().column === 'RESISTANCE' && p != null && this.card().targetPrice < p;
  });

  readonly maxComment = MAX_CARD_COMMENT;
  readonly commentDraft = signal('');

  // Per-field server values: a save of one field (new card object) must not reset what is being typed in the other.
  private readonly serverTarget = computed(() => this.card().targetPrice);
  private readonly serverComment = computed(() => this.card().comment ?? '');

  constructor() {
    // Sync each editable field only when its own server value changes.
    effect(() => this.draft.set(this.serverTarget().toFixed(2)));
    effect(() => this.commentDraft.set(this.serverComment()));
  }

  cancelComment(): void {
    this.commentDraft.set(this.card().comment ?? '');
    this.error.set(null);
  }

  commitComment(): void {
    const result = this.kanban.updateComment(this.card(), this.commentDraft());
    if (result === null) {
      this.commentDraft.set(this.card().comment ?? '');
      return;
    }
    if (typeof result === 'string') {
      this.error.set(result);
      return;
    }
    this.busy.set(true);
    result.subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.error.set(null);
        this.commentDraft.set(saved.comment ?? '');
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Update failed'));
      },
    });
  }

  cancel(): void {
    this.draft.set(this.card().targetPrice.toFixed(2));
    this.error.set(null);
  }

  commit(): void {
    const result = this.kanban.updateTargetPrice(this.card(), this.draft());
    if (!isObservable(result)) {
      if (result.ok) {
        this.draft.set(result.value.toFixed(2));
      } else {
        this.error.set(result.error);
      }
      return;
    }
    this.busy.set(true);
    result.subscribe({
      next: () => {
        this.busy.set(false);
        this.error.set(null);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Update failed'));
      },
    });
  }

  remove(): void {
    this.busy.set(true);
    this.kanban.delete(this.card().id).subscribe({
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Delete failed'));
      },
    });
  }
}
