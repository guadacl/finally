import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeEventSourceFactory } from '../../testing/fake-event-source';
import { EVENT_SOURCE_FACTORY } from '../core/event-source';
import { WatchlistService } from '../core/watchlist.service';
import { WatchlistTableComponent } from './watchlist-table.component';

describe('WatchlistTableComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: fakeEventSourceFactory },
      ],
    });
    TestBed.inject(WatchlistService).items.set([
      { ticker: 'ENPH', name: 'Enphase Energy, Inc.', addedAt: '2026-11-05T10:00:00Z' },
      { ticker: 'ZZZZ', addedAt: '2026-11-05T10:00:00Z' },
    ]);
  });

  async function render(showName: boolean) {
    const fixture = TestBed.createComponent(WatchlistTableComponent);
    fixture.componentRef.setInput('showName', showName);
    fixture.detectChanges();
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    const heads = Array.from(root.querySelectorAll('thead th')).map((th) => th.textContent!.trim()).filter(Boolean);
    const q = (id: string) => root.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
    return { heads, q };
  }

  it('shows the company name column when asked (Watchlists page)', async () => {
    const { heads, q } = await render(true);
    expect(heads).toEqual(['Ticker', 'Name', 'Price', 'Chg %', 'Trend']);
    expect(q('watchlist-name-ENPH')!.textContent!.trim()).toBe('Enphase Energy, Inc.');
    expect(q('watchlist-name-ENPH')!.getAttribute('title')).toBe('Enphase Energy, Inc.');
    expect(q('watchlist-name-ZZZZ')!.textContent!.trim()).toBe('—');
  });

  it('filters rows on the frontend from the funnel icon in the table corner', async () => {
    const fixture = TestBed.createComponent(WatchlistTableComponent);
    fixture.componentRef.setInput('showName', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    const q = (id: string) => root.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
    const rows = () => Array.from(root.querySelectorAll('tbody tr[data-testid^="watchlist-row-"]')).map((r) => r.getAttribute('data-testid'));

    // no second search field any more: only the icon, the filter opens on click
    expect(q('watchlist-search')).toBeNull();
    expect(q('watchlist-filter')).toBeNull();
    q('watchlist-filter-toggle')!.click();
    await fixture.whenStable();
    const filter = q('watchlist-filter') as HTMLInputElement;
    expect(document.activeElement).toBe(filter); // ready to type
    const type = async (v: string) => {
      filter.value = v;
      filter.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };

    await type('enphase');
    expect(rows()).toEqual(['watchlist-row-ENPH']);
    expect(q('watchlist-filter-toggle')!.className).toContain('text-accent'); // active filter is highlighted
    await type('zz');
    expect(rows()).toEqual(['watchlist-row-ZZZZ']);
    await type('nothing');
    expect(rows()).toEqual([]);
    expect(q('watchlist-no-match')!.textContent).toContain('nothing');

    // closing the panel keeps the filter; Esc clears it and closes the panel
    q('watchlist-filter-toggle')!.click();
    await fixture.whenStable();
    expect(q('watchlist-filter-panel')).toBeNull();
    expect(rows()).toEqual([]);
    q('watchlist-filter-toggle')!.click();
    await fixture.whenStable();
    q('watchlist-filter')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(q('watchlist-filter-panel')).toBeNull();
    expect(rows()).toEqual(['watchlist-row-ENPH', 'watchlist-row-ZZZZ']);
    expect(q('watchlist-filter-toggle')!.className).toContain('text-muted');
  });

  it('leaves the name out by default (Markets panel)', async () => {
    const { heads, q } = await render(false);
    expect(heads).not.toContain('Name');
    expect(q('watchlist-name-ENPH')).toBeNull();
  });
});
