export type RsiZone = 'overbought' | 'oversold' | 'neutral' | 'unknown';

/** Classic RSI thresholds: ≥ 70 overbought, ≤ 30 oversold. */
export function rsiZone(rsi: number | null | undefined): RsiZone {
  if (rsi == null || !Number.isFinite(rsi)) return 'unknown';
  return rsi >= 70 ? 'overbought' : rsi <= 30 ? 'oversold' : 'neutral';
}

/** Cell color: overbought red, oversold green, otherwise muted. */
export function rsiClass(rsi: number | null | undefined): string {
  const zone = rsiZone(rsi);
  return zone === 'overbought' ? 'text-down font-semibold' : zone === 'oversold' ? 'text-up font-semibold' : 'text-muted';
}

export function rsiTitle(rsi: number | null | undefined): string {
  const zone = rsiZone(rsi);
  if (zone === 'unknown') return 'RSI needs a Massive API key (daily closes); it appears shortly after the stock is added';
  return zone === 'overbought' ? 'Overbought (RSI ≥ 70)' : zone === 'oversold' ? 'Oversold (RSI ≤ 30)' : 'Neutral (30 < RSI < 70)';
}

export function fmtRsi(rsi: number | null | undefined): string {
  return rsi != null && Number.isFinite(rsi) ? rsi.toFixed(1) : '—';
}
