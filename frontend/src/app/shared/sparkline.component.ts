import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PricePoint } from '../core/price-stream.service';

/** Build an SVG polyline `points` string for the given values. */
export function sparkPoints(values: readonly number[], width: number, height: number): string {
  if (values.length < 2) return '';
  let min = Infinity;
  let max = -Infinity;
  for (const v of values) {
    if (v < min) min = v;
    if (v > max) max = v;
  }
  const range = max - min || 1;
  const step = width / (values.length - 1);
  return values
    .map((v, i) => `${(i * step).toFixed(1)},${(height - 1 - ((v - min) / range) * (height - 2)).toFixed(1)}`)
    .join(' ');
}

const MAX_SPARK_POINTS = 120;

@Component({
  selector: 'app-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.width]="width()" [attr.height]="height()" [attr.viewBox]="'0 0 ' + width() + ' ' + height()" class="block">
      @if (points()) {
        <polyline [attr.points]="points()" fill="none" [attr.stroke]="color()" stroke-width="1.25" stroke-linejoin="round" />
      } @else {
        <line x1="0" [attr.y1]="height() / 2" [attr.x2]="width()" [attr.y2]="height() / 2" stroke="#2a3240" stroke-dasharray="2 3" />
      }
    </svg>
  `,
})
export class SparklineComponent {
  readonly history = input<readonly PricePoint[]>([]);
  readonly width = input(96);
  readonly height = input(24);

  private readonly values = computed(() => this.history().slice(-MAX_SPARK_POINTS).map((p) => p.value));
  readonly points = computed(() => sparkPoints(this.values(), this.width(), this.height()));
  readonly color = computed(() => {
    const v = this.values();
    if (v.length < 2) return '#7d8794';
    return v[v.length - 1] >= v[0] ? '#26c281' : '#ef5350';
  });
}
