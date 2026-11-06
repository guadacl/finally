import { ChangeDetectionStrategy, Component, input, model, signal } from '@angular/core';
import { SearchBoxComponent } from './search-box.component';

/**
 * Funnel icon that opens a small frontend-only filter field (ticker or company name) under it. Place it in a
 * table's top-right corner. The icon is yellow while a filter is active; clicking it again hides the field but
 * keeps the filter; Esc or × clears it. Test ids: {@code <testId>-toggle}, {@code <testId>-panel}, {@code <testId>}.
 */
@Component({
  selector: 'app-filter-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SearchBoxComponent],
  host: { class: 'relative inline-flex normal-case tracking-normal' },
  template: `
    <button
      type="button"
      class="flex h-5 w-5 cursor-pointer items-center justify-center rounded hover:bg-edge"
      [attr.data-testid]="testId() + '-toggle'"
      [class.text-accent]="active()"
      [class.text-muted]="!active()"
      [attr.title]="active() ? 'Filter: ' + query() : 'Filter the list'"
      aria-label="Filter the list"
      [attr.aria-expanded]="open()"
      (click)="open.set(!open())"
    >
      <svg viewBox="0 0 16 16" class="h-3.5 w-3.5" fill="currentColor" aria-hidden="true">
        <path d="M1.5 2h13a.5.5 0 0 1 .39.81L10 9v4.5a.5.5 0 0 1-.28.45l-3 1.5A.5.5 0 0 1 6 15V9L1.11 2.81A.5.5 0 0 1 1.5 2z" />
      </svg>
    </button>
    @if (open()) {
      <div
        class="absolute top-6 right-0 z-30 w-56 rounded border border-edge bg-panel-2 p-1.5 shadow-lg"
        [attr.data-testid]="testId() + '-panel'"
      >
        <app-search-box
          [(query)]="query"
          [fullWidth]="true"
          [autofocus]="true"
          [placeholder]="placeholder()"
          [testId]="testId()"
          (escaped)="clear()"
        />
      </div>
    }
  `,
})
export class FilterButtonComponent {
  readonly query = model('');
  readonly testId = input('filter');
  readonly placeholder = input('Filter by ticker or name');
  readonly open = signal(false);

  active(): boolean {
    return !!this.query().trim();
  }

  /** Esc in the field: clear the filter and close it. */
  clear(): void {
    this.query.set('');
    this.open.set(false);
  }
}
