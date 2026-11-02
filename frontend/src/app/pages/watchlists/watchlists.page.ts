import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { WatchlistService } from '../../core/watchlist.service';
import { WatchlistTableComponent } from '../../shared/watchlist-table.component';

@Component({
  selector: 'app-watchlists-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WatchlistTableComponent],
  host: { class: 'block h-full' },
  template: `
    <section class="panel mx-auto flex h-full max-w-4xl flex-col overflow-hidden">
      <div class="panel-title">
        <span>Manage watchlist</span>
        <span class="num normal-case tracking-normal">{{ watchlist.items().length }} tickers · prices stream live</span>
      </div>
      <app-watchlist-table class="min-h-0 flex-1" [removable]="true" [showAdd]="true" />
    </section>
  `,
})
export class WatchlistsPage {
  readonly watchlist = inject(WatchlistService);
}
