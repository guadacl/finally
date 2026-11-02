export interface TreemapInput<T> {
  value: number;
  data: T;
}

export interface TreemapRect<T> {
  x: number;
  y: number;
  w: number;
  h: number;
  data: T;
}

/**
 * Squarified treemap layout (Bruls et al.) in a w×h box.
 * Items with non-positive values are dropped. Output order follows descending value.
 */
export function squarify<T>(items: readonly TreemapInput<T>[], width: number, height: number): TreemapRect<T>[] {
  const sorted = items.filter((i) => i.value > 0).sort((a, b) => b.value - a.value);
  const total = sorted.reduce((s, i) => s + i.value, 0);
  if (!sorted.length || total <= 0 || width <= 0 || height <= 0) return [];
  const scale = (width * height) / total;
  const areas = sorted.map((i) => ({ area: i.value * scale, data: i.data }));

  const out: TreemapRect<T>[] = [];
  let x = 0;
  let y = 0;
  let w = width;
  let h = height;
  let row: { area: number; data: T }[] = [];

  const worst = (r: { area: number }[], side: number): number => {
    const s = r.reduce((a, b) => a + b.area, 0);
    let max = 0;
    let min = Infinity;
    for (const it of r) {
      max = Math.max(max, it.area);
      min = Math.min(min, it.area);
    }
    const s2 = s * s;
    const side2 = side * side;
    return Math.max((side2 * max) / s2, s2 / (side2 * min));
  };

  const layoutRow = (r: { area: number; data: T }[]) => {
    const s = r.reduce((a, b) => a + b.area, 0);
    if (w >= h) {
      // vertical strip on the left
      const stripW = s / h;
      let cy = y;
      for (const it of r) {
        const ih = it.area / stripW;
        out.push({ x, y: cy, w: stripW, h: ih, data: it.data });
        cy += ih;
      }
      x += stripW;
      w -= stripW;
    } else {
      const stripH = s / w;
      let cx = x;
      for (const it of r) {
        const iw = it.area / stripH;
        out.push({ x: cx, y, w: iw, h: stripH, data: it.data });
        cx += iw;
      }
      y += stripH;
      h -= stripH;
    }
  };

  for (const item of areas) {
    const side = Math.min(w, h);
    if (!row.length || worst([...row, item], side) <= worst(row, side)) {
      row.push(item);
    } else {
      layoutRow(row);
      row = [item];
    }
  }
  if (row.length) layoutRow(row);
  return out;
}

/** Heatmap fill color for a P&L percent: green/red with intensity by magnitude (saturates at ±10%). */
export function pnlColor(pct: number): string {
  const t = Math.min(Math.abs(pct) / 10, 1);
  const alpha = 0.18 + 0.62 * t;
  if (pct > 0) return `rgba(38, 194, 129, ${alpha.toFixed(3)})`;
  if (pct < 0) return `rgba(239, 83, 80, ${alpha.toFixed(3)})`;
  return 'rgba(125, 135, 148, 0.25)';
}
