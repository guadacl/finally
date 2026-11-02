import { Pipe, PipeTransform } from '@angular/core';

const money = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const qty = new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 });

export function fmtMoney(v: number | null | undefined, sign = false): string {
  if (v == null || !Number.isFinite(v)) return '—';
  const s = money.format(Math.abs(v));
  const prefix = v < 0 ? '-' : sign && v > 0 ? '+' : '';
  return `${prefix}$${s}`;
}

export function fmtPrice(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—';
  return money.format(v);
}

export function fmtPct(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—';
  return `${v > 0 ? '+' : ''}${v.toFixed(2)}%`;
}

export function fmtQty(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—';
  return qty.format(v);
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(v: number | null | undefined, sign = false): string {
    return fmtMoney(v, sign);
  }
}

@Pipe({ name: 'price' })
export class PricePipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return fmtPrice(v);
  }
}

@Pipe({ name: 'pct' })
export class PctPipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return fmtPct(v);
  }
}

@Pipe({ name: 'qty' })
export class QtyPipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return fmtQty(v);
  }
}

/** Tailwind text color class for a signed value. */
export function signClass(v: number | null | undefined): string {
  if (v == null || v === 0) return 'text-muted';
  return v > 0 ? 'text-up' : 'text-down';
}
