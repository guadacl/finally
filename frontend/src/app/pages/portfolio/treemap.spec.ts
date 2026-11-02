import { pnlColor, squarify } from './treemap';

describe('squarify', () => {
  it('fills the box with areas proportional to value', () => {
    const rects = squarify(
      [
        { value: 6, data: 'a' },
        { value: 3, data: 'b' },
        { value: 1, data: 'c' },
      ],
      100,
      100,
    );
    expect(rects.length).toBe(3);
    const area = (d: string) => {
      const r = rects.find((x) => x.data === d)!;
      return r.w * r.h;
    };
    expect(area('a')).toBeCloseTo(6000);
    expect(area('b')).toBeCloseTo(3000);
    expect(area('c')).toBeCloseTo(1000);
    for (const r of rects) {
      expect(r.x).toBeGreaterThanOrEqual(-1e-9);
      expect(r.y).toBeGreaterThanOrEqual(-1e-9);
      expect(r.x + r.w).toBeLessThanOrEqual(100 + 1e-9);
      expect(r.y + r.h).toBeLessThanOrEqual(100 + 1e-9);
    }
  });

  it('drops non-positive values and handles empty input', () => {
    expect(squarify([{ value: 0, data: 1 }], 10, 10)).toEqual([]);
    expect(squarify([], 10, 10)).toEqual([]);
  });

  it('colors profit green, loss red', () => {
    expect(pnlColor(5)).toContain('38, 194, 129');
    expect(pnlColor(-5)).toContain('239, 83, 80');
  });
});
