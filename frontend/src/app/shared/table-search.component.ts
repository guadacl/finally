import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/** Lower-cases and strips accents and punctuation, so "Coca-Cola" and "coca cola" compare equal. */
export function normalizeSearchText(s: string | null | undefined): string {
  return (s ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9.]+/g, ' ')
    .trim();
}

/**
 * Full-text match of a table row on its ticker and company name: every word of the query must appear
 * in the ticker or the name (any order, partial words allowed). An empty query matches everything.
 */
export function matchesSearch(query: string | null | undefined, ticker: string, name: string | null | undefined): boolean {
  const words = normalizeSearchText(query).split(' ').filter(Boolean);
  if (words.length === 0) return true;
  const haystack = `${normalizeSearchText(ticker)} ${normalizeSearchText(name)}`;
  return words.every((w) => haystack.includes(w));
}

/** Search box shown above a table; two-way bound to the table's query signal. */
@Component({
  selector: 'app-table-search',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'relative block' },
  template: `
    <span class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-[11px] text-muted">⌕</span>
    <input
      type="search"
      class="field w-full py-1 pr-2 pl-6 text-xs"
      autocomplete="off"
      [attr.data-testid]="testId()"
      [attr.aria-label]="placeholder()"
      [placeholder]="placeholder()"
      [value]="query()"
      (input)="query.set($any($event.target).value)"
      (keydown.escape)="query.set('')"
    />
  `,
})
export class TableSearchComponent {
  readonly query = model('');
  readonly placeholder = input('Search ticker or name');
  readonly testId = input<string | null>(null);
}
