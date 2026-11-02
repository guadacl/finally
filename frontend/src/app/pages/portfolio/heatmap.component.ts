import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Position } from '../../api/models';
import { MoneyPipe, PctPipe } from '../../shared/format';
import { pnlColor, squarify } from './treemap';

@Component({
  selector: 'app-heatmap',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PctPipe, MoneyPipe],
  host: { class: 'block relative', 'data-testid': 'heatmap' },
  template: `
    @for (r of rects(); track r.data.ticker) {
      <div
        class="absolute overflow-hidden border border-bg p-1.5 transition-[background-color] duration-500"
        [attr.data-testid]="'heatmap-cell-' + r.data.ticker"
        [style.left.%]="r.x"
        [style.top.%]="r.y"
        [style.width.%]="r.w"
        [style.height.%]="r.h"
        [style.background-color]="color(r.data.unrealizedPnlPercent)"
        [attr.title]="r.data.ticker + ' · ' + (r.data.marketValue | money) + ' · ' + (r.data.unrealizedPnlPercent | pct)"
      >
        <div class="text-xs font-bold">{{ r.data.ticker }}</div>
        <div class="num text-[11px]">{{ r.data.unrealizedPnlPercent | pct }}</div>
        <div class="num text-[10px] opacity-75">{{ weight(r.data).toFixed(1) }}% wt</div>
      </div>
    } @empty {
      <div class="absolute inset-0 flex items-center justify-center text-xs text-muted">No positions yet — buy something</div>
    }
  `,
})
export class HeatmapComponent {
  readonly positions = input<readonly Position[]>([]);

  private readonly totalMv = computed(() => this.positions().reduce((s, p) => s + Math.max(p.marketValue, 0), 0));
  readonly rects = computed(() =>
    squarify(
      this.positions().map((p) => ({ value: p.marketValue, data: p })),
      100,
      100,
    ),
  );

  color(pct: number): string {
    return pnlColor(pct);
  }

  /** Portfolio weight in percent (by market value). */
  weight(p: Position): number {
    const t = this.totalMv();
    return t > 0 ? (p.marketValue / t) * 100 : 0;
  }
}
