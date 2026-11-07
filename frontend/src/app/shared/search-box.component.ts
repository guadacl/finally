import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, input, model, output, viewChild } from '@angular/core';

/** Lower-cases and strips accents and punctuation, so "Coca-Cola" and "coca cola" compare equal (dots kept for BRK.B). */
export function normalizeSearchText(s: string | null | undefined): string {
  return (s ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9.]+/g, ' ')
    .trim();
}

/**
 * True when every word of the query is found in the ticker or the company name (case-, accent- and
 * punctuation-insensitive). An empty query matches everything. "enph", "enphase", "energy enp" all match
 * ENPH / Enphase Energy, Inc.
 */
export function matchesSearch(query: string | null | undefined, ticker: string, name?: string | null): boolean {
  const words = normalizeSearchText(query).split(' ').filter(Boolean);
  if (words.length === 0) return true;
  const haystack = `${normalizeSearchText(ticker)} ${normalizeSearchText(name)}`;
  return words.every((w) => haystack.includes(w));
}

/** Small search field for a table's title bar; filters by ticker or company name. */
@Component({
  selector: 'app-search-box',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'relative inline-flex items-center normal-case tracking-normal',
    '[class.w-40]': '!fullWidth()',
    '[class.w-full]': 'fullWidth()',
  },
  template: `
    <input
      #field
      type="text"
      class="field h-7 w-full py-0 pr-6 text-xs"
      autocomplete="off"
      [attr.data-testid]="testId()"
      [attr.aria-label]="placeholder()"
      [placeholder]="placeholder()"
      [value]="query()"
      (input)="query.set($any($event.target).value)"
      (keydown.escape)="query.set(''); escaped.emit()"
    />
    @if (query()) {
      <button
        type="button"
        class="absolute right-1.5 text-sm leading-none text-muted hover:text-fg"
        [attr.data-testid]="testId() + '-clear'"
        aria-label="Clear search"
        title="Clear search"
        (click)="query.set('')"
      >
        &times;
      </button>
    }
  `,
})
export class SearchBoxComponent {
  readonly query = model('');
  readonly placeholder = input('Search ticker or name');
  readonly testId = input('search');
  readonly fullWidth = input(false);
  /** Focus the field when it appears (e.g. a filter opened from an icon). */
  readonly autofocus = input(false);
  /** Esc pressed in the field (after clearing it). */
  readonly escaped = output<void>();

  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');

  constructor() {
    afterNextRender(() => {
      if (this.autofocus()) this.field()?.nativeElement.focus();
    });
  }
}
