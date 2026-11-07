import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import {
  AreaSeries,
  ColorType,
  CrosshairMode,
  IChartApi,
  ISeriesApi,
  LineData,
  UTCTimestamp,
  createChart,
} from 'lightweight-charts';
import { PricePoint } from '../core/price-stream.service';
import { Theme, ThemeService } from '../core/theme.service';

/** Axis/grid colors per UI theme. */
function chartChrome(theme: Theme) {
  const light = theme === 'light';
  const edge = light ? '#d5dbe3' : '#2a3240';
  const grid = light ? 'rgba(213, 219, 227, 0.6)' : 'rgba(42, 50, 64, 0.5)';
  return {
    layout: { textColor: light ? '#5f6b7a' : '#7d8794' },
    grid: { vertLines: { color: grid }, horzLines: { color: grid } },
    rightPriceScale: { borderColor: edge },
    timeScale: { borderColor: edge },
  };
}

/** Convert ms points to strictly increasing per-second chart data (last value per second wins). */
export function toChartData(points: readonly PricePoint[]): LineData[] {
  const out: LineData[] = [];
  for (const p of points) {
    const t = Math.floor(p.time / 1000) as UTCTimestamp;
    const last = out[out.length - 1];
    if (last && (last.time as number) === t) {
      last.value = p.value;
    } else if (!last || t > (last.time as number)) {
      out.push({ time: t, value: p.value });
    }
  }
  return out;
}

function hexToRgba(hex: string, alpha: number): string {
  const n = parseInt(hex.replace('#', ''), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/** Thin wrapper around a lightweight-charts area series. */
@Component({
  selector: 'app-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block relative' },
  template: `
    <div #host class="absolute inset-0"></div>
    @if (!hasData()) {
      <div class="absolute inset-0 flex items-center justify-center text-xs text-muted">{{ emptyText() }}</div>
    }
  `,
})
export class LineChartComponent implements OnDestroy {
  readonly points = input<readonly PricePoint[]>([]);
  readonly color = input('#209dd7');
  /** Changing this key forces a full reset of the series (e.g. when the ticker changes). */
  readonly resetKey = input<string>('');
  readonly emptyText = input('Waiting for data…');
  readonly showTime = input(true);

  private readonly theme = inject(ThemeService).theme;
  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private chart: IChartApi | null = null;
  private series: ISeriesApi<'Area'> | null = null;
  private readonly ready = signal(false);
  readonly hasData = signal(false);
  private lastKey: string | null = null;
  private lastTime = -1;

  constructor() {
    afterNextRender(() => {
      const el = this.host().nativeElement;
      const chrome = chartChrome(this.theme());
      this.chart = createChart(el, {
        autoSize: true,
        layout: {
          background: { type: ColorType.Solid, color: 'transparent' },
          textColor: chrome.layout.textColor,
          fontSize: 11,
          attributionLogo: false,
        },
        grid: chrome.grid,
        rightPriceScale: chrome.rightPriceScale,
        timeScale: { ...chrome.timeScale, timeVisible: this.showTime(), secondsVisible: true },
        crosshair: { mode: CrosshairMode.Normal },
      });
      this.series = this.chart.addSeries(AreaSeries, {
        lineColor: this.color(),
        topColor: hexToRgba(this.color(), 0.35),
        bottomColor: hexToRgba(this.color(), 0.02),
        lineWidth: 2,
        priceLineVisible: true,
      });
      this.ready.set(true);
    });

    effect(() => {
      const theme = this.theme();
      if (!this.ready() || !this.chart) return;
      this.chart.applyOptions(chartChrome(theme));
    });

    effect(() => {
      const color = this.color();
      if (!this.ready() || !this.series) return;
      this.series.applyOptions({
        lineColor: color,
        topColor: hexToRgba(color, 0.35),
        bottomColor: hexToRgba(color, 0.02),
      });
    });

    effect(() => {
      const pts = this.points();
      const key = this.resetKey();
      if (!this.ready() || !this.series || !this.chart) return;
      const data = toChartData(pts);
      this.hasData.set(data.length > 0);
      if (key !== this.lastKey || this.lastTime < 0 || !data.length || (data[0].time as number) > this.lastTime) {
        this.series.setData(data);
        this.chart.timeScale().fitContent();
      } else {
        for (const d of data) {
          if ((d.time as number) >= this.lastTime) this.series.update(d);
        }
      }
      this.lastKey = key;
      this.lastTime = data.length ? (data[data.length - 1].time as number) : -1;
    });
  }

  ngOnDestroy(): void {
    this.chart?.remove();
    this.chart = null;
    this.series = null;
  }
}
