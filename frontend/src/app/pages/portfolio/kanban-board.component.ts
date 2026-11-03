import { CdkDrag, CdkDragDrop, CdkDragPlaceholder, CdkDropList, CdkDropListGroup, transferArrayItem } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { COLUMN_LABEL, KANBAN_COLUMNS, KanbanCard, KanbanColumn, errorMessage } from '../../api/models';
import { KanbanService, sortByDistance, validateTargetPrice } from '../../core/kanban.service';
import { PriceStreamService } from '../../core/price-stream.service';
import { TICKER_PATTERN, normalizeTicker } from '../../core/watchlist.service';
import { KanbanCardComponent } from './kanban-card.component';

@Component({
  selector: 'app-kanban-board',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, CdkDropListGroup, CdkDropList, CdkDrag, CdkDragPlaceholder, KanbanCardComponent],
  host: { class: 'flex min-h-0 flex-col' },
  template: `
    <form class="flex flex-wrap items-center gap-2 border-b border-edge p-2" (ngSubmit)="create()">
      <input
        name="kTicker"
        data-testid="kanban-add-ticker"
        class="field num w-24 uppercase"
        placeholder="Ticker"
        autocomplete="off"
        maxlength="10"
        [ngModel]="ticker()"
        (ngModelChange)="ticker.set($event); error.set(null)"
      />
      <select
        name="kColumn"
        data-testid="kanban-add-column"
        class="field"
        [ngModel]="column()"
        (ngModelChange)="column.set($event)"
      >
        <option value="SUPPORT">{{ labels.SUPPORT }}</option>
        <option value="RESISTANCE">{{ labels.RESISTANCE }}</option>
      </select>
      <input
        name="kPrice"
        data-testid="kanban-add-price"
        class="field num w-28"
        placeholder="Target price"
        inputmode="decimal"
        autocomplete="off"
        [ngModel]="price()"
        (ngModelChange)="price.set($event); error.set(null)"
      />
      <button type="submit" data-testid="kanban-add-button" class="btn btn-submit" [disabled]="busy()">Add card</button>
      @if (livePrice() != null) {
        <span class="num text-xs text-muted">{{ normalized() }} now {{ livePrice()!.toFixed(2) }}</span>
      }
      @if (error(); as e) {
        <span data-testid="kanban-add-error" class="text-xs text-down">{{ e }}</span>
      }
    </form>

    <div class="grid min-h-0 flex-1 grid-cols-2 gap-2 p-2" cdkDropListGroup>
      @for (col of columns; track col) {
        <div class="flex min-h-0 flex-col rounded border border-edge bg-bg/60">
          <div class="flex items-center justify-between border-b border-edge px-2 py-1.5">
            <span class="flex items-center gap-1.5 text-[11px] font-semibold tracking-widest uppercase" [class]="col === 'SUPPORT' ? 'text-down' : 'text-up'">
              <span class="h-1.5 w-1.5 rounded-full" [class]="col === 'SUPPORT' ? 'bg-down' : 'bg-up'"></span>
              {{ labels[col] }}
            </span>
            <span class="num text-[10px] text-muted">{{ list(col).length }}</span>
          </div>
          <div
            cdkDropList
            [id]="'kanban-list-' + col"
            [cdkDropListData]="list(col)"
            cdkDropListSortingDisabled
            (cdkDropListDropped)="drop($event, col)"
            [attr.data-testid]="'kanban-column-' + col"
            class="kanban-list flex min-h-[120px] flex-1 flex-col gap-1.5 overflow-y-auto p-1.5"
          >
            @for (card of list(col); track card.id) {
              <app-kanban-card cdkDrag [cdkDragData]="card" [card]="card" class="kanban-drag">
                <div *cdkDragPlaceholder class="h-16 rounded border border-dashed border-primary/60 bg-primary/5"></div>
              </app-kanban-card>
            } @empty {
              <div class="m-auto text-center text-[11px] text-muted">
                {{ col === 'SUPPORT' ? 'Alerts when the price falls to the target' : 'Alerts when the price rises to the target' }}<br />Drop cards here
              </div>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .cdk-drag-preview {
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
      opacity: 0.95;
    }
    .cdk-drag-animating {
      transition: transform 200ms cubic-bezier(0, 0, 0.2, 1);
    }
    .kanban-list.cdk-drop-list-dragging .kanban-drag:not(.cdk-drag-placeholder) {
      transition: transform 200ms cubic-bezier(0, 0, 0.2, 1);
    }
  `,
})
export class KanbanBoardComponent {
  private readonly kanban = inject(KanbanService);
  private readonly prices = inject(PriceStreamService);

  readonly columns = KANBAN_COLUMNS;
  readonly labels = COLUMN_LABEL;
  readonly ticker = signal('');
  readonly column = signal<KanbanColumn>('SUPPORT');
  readonly price = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly normalized = computed(() => normalizeTicker(this.ticker() ?? ''));
  readonly livePrice = computed(() => (this.normalized() ? this.prices.price(this.normalized()) : null));

  /** Live price of a card (stream first, then the price the server sent with the card). */
  private readonly priceOf = (card: KanbanCard) => this.prices.state(card.ticker)()?.price ?? card.currentPrice ?? null;

  /** Each column ordered by DIST ascending; re-sorts as prices stream in. */
  private readonly sorted = computed<Record<KanbanColumn, KanbanCard[]>>(() => ({
    SUPPORT: sortByDistance(this.kanban.support(), this.priceOf),
    RESISTANCE: sortByDistance(this.kanban.resistance(), this.priceOf),
  }));

  list(col: KanbanColumn): KanbanCard[] {
    return this.sorted()[col];
  }

  create(): void {
    const ticker = this.normalized();
    if (!ticker || !TICKER_PATTERN.test(ticker)) {
      this.error.set('Enter a valid ticker');
      return;
    }
    const v = validateTargetPrice(this.price());
    if (!v.ok) {
      this.error.set(v.error);
      return;
    }
    this.busy.set(true);
    this.kanban.create(ticker, this.column(), v.value).subscribe({
      next: () => {
        this.busy.set(false);
        this.ticker.set('');
        this.price.set('');
        this.error.set(null);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not create card'));
      },
    });
  }

  drop(event: CdkDragDrop<KanbanCard[]>, target: KanbanColumn): void {
    const source: KanbanColumn = event.previousContainer.id.endsWith('RESISTANCE') ? 'RESISTANCE' : 'SUPPORT';
    // Order inside a column is automatic (DIST ascending): only moves between columns count.
    if (source === target) return;
    const lists: Record<KanbanColumn, KanbanCard[]> = {
      SUPPORT: [...this.kanban.support()],
      RESISTANCE: [...this.kanban.resistance()],
    };
    const moved = lists[source].findIndex((c) => c.id === (event.item.data as KanbanCard).id);
    if (moved < 0) return;
    transferArrayItem(lists[source], lists[target], moved, lists[target].length);
    this.kanban.reorder(lists).subscribe({
      error: () => this.kanban.load(), // resync with the server on failure
    });
  }
}
