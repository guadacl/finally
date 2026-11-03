import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { COLUMN_LABEL, KanbanColumn } from '../api/models';
import { ToastService } from '../core/toast.service';
import { PricePipe } from '../shared/format';

@Component({
  selector: 'app-toasts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PricePipe],
  template: `
    <div class="pointer-events-none fixed top-14 right-4 z-50 flex w-80 flex-col gap-2" aria-live="polite">
      @for (t of toasts.toasts(); track t.id) {
        <div
          data-testid="toast"
          [attr.data-type]="t.type"
          role="status"
          class="toast-in pointer-events-auto rounded-md border bg-panel-2/95 px-3 py-2 shadow-lg shadow-black/40 backdrop-blur"
          [class]="borderClass(t.type)"
        >
          <div class="flex items-start gap-2">
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2 text-xs">
                @if (t.ticker) {
                  <span class="font-bold">{{ t.ticker }}</span>
                }
                @if (t.column) {
                  <span
                    class="rounded px-1 text-[9px] font-semibold tracking-wider uppercase"
                    [class]="t.column === 'SUPPORT' ? 'bg-down/15 text-down' : 'bg-up/15 text-up'"
                    >{{ columnLabel(t.column) }}</span
                  >
                }
                <span class="ml-auto text-[9px] tracking-widest text-muted uppercase">{{ label(t.type) }}</span>
              </div>
              @if (t.targetPrice != null) {
                <div class="num mt-1 flex gap-3 text-[11px] text-muted">
                  <span
                    >Target <span class="text-fg">{{ t.targetPrice | price }}</span></span
                  >
                  @if (t.price != null) {
                    <span
                      >Now <span class="text-fg">{{ t.price | price }}</span></span
                    >
                  }
                </div>
              }
              <div class="mt-1 text-[11.5px] leading-snug">{{ t.message }}</div>
            </div>
            <button
              type="button"
              class="cursor-pointer px-1 leading-none text-muted hover:text-fg"
              aria-label="Dismiss"
              data-testid="toast-close"
              (click)="toasts.dismiss(t.id)"
            >
              &times;
            </button>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .toast-in {
      animation: toast-in 200ms ease-out;
    }
    @keyframes toast-in {
      from {
        opacity: 0;
        transform: translateX(16px);
      }
      to {
        opacity: 1;
        transform: none;
      }
    }
  `,
})
export class ToastsComponent {
  readonly toasts = inject(ToastService);

  borderClass(type: string): string {
    switch (type) {
      case 'TARGET_HIT':
        return 'border-accent';
      case 'ERROR':
        return 'border-down/70';
      default:
        return 'border-edge-strong';
    }
  }

  label(type: string): string {
    switch (type) {
      case 'TARGET_HIT':
        return 'Target hit';
      case 'CARD_CREATED':
        return 'Card created';
      case 'CARD_MOVED':
        return 'Card moved';
      case 'CARD_DELETED':
        return 'Card deleted';
      default:
        return type.toLowerCase();
    }
  }

  columnLabel(column: KanbanColumn): string {
    return COLUMN_LABEL[column] ?? column;
  }
}
