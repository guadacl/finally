import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { PortfolioService } from '../core/portfolio.service';
import { PriceStreamService } from '../core/price-stream.service';
import { ThemeService } from '../core/theme.service';
import { MoneyPipe, signClass } from '../shared/format';
import { PriceFlashDirective } from '../shared/price-flash.directive';

@Component({
  selector: 'app-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, MoneyPipe, PriceFlashDirective],
  template: `
    <header class="flex h-12 items-center gap-6 border-b border-edge bg-panel-2 px-4">
      <a routerLink="/markets" class="flex items-center gap-2 select-none">
        <span class="flex h-7 w-7 items-center justify-center rounded bg-accent text-sm font-black text-bg">F</span>
        <span class="text-base font-bold tracking-tight">Fin<span class="text-accent">Ally</span></span>
        <span class="hidden text-[10px] tracking-widest text-muted uppercase lg:inline">AI Trading Workstation</span>
      </a>

      <nav class="flex h-full items-stretch gap-1">
        @for (item of nav; track item.path) {
          <a
            [routerLink]="item.path"
            routerLinkActive="!text-fg !border-accent"
            [attr.data-testid]="'nav-' + item.id"
            class="flex items-center border-b-2 border-transparent px-3 text-xs font-semibold tracking-wider text-muted uppercase transition-colors hover:text-fg"
            >{{ item.label }}</a
          >
        }
      </nav>

      <div class="ml-auto flex items-center gap-5">
        <div class="flex flex-col items-end leading-tight">
          <span class="text-[10px] tracking-widest text-muted uppercase">Portfolio</span>
          <span class="num rounded px-1 text-sm font-semibold" data-testid="header-total" [appPriceFlash]="total()">{{
            total() | money
          }}</span>
        </div>
        <div class="hidden flex-col items-end leading-tight sm:flex">
          <span class="text-[10px] tracking-widest text-muted uppercase">Unrealized</span>
          <span class="num text-sm" [class]="pnlClass()">{{ pnl() | money: true }}</span>
        </div>
        <div class="flex flex-col items-end leading-tight">
          <span class="text-[10px] tracking-widest text-muted uppercase">Cash</span>
          <span class="num text-sm" data-testid="header-cash">{{ cash() | money }}</span>
        </div>
        <div
          class="flex items-center gap-1.5"
          data-testid="connection-status"
          [attr.data-status]="status()"
          [attr.title]="'Price stream: ' + status()"
        >
          <span class="relative flex h-2.5 w-2.5">
            @if (status() === 'connected') {
              <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-up opacity-40"></span>
            }
            <span class="relative inline-flex h-2.5 w-2.5 rounded-full" [class]="dotClass()"></span>
          </span>
          <span class="hidden text-[10px] tracking-widest text-muted uppercase md:inline">{{ status() }}</span>
        </div>
        <button
          type="button"
          data-testid="theme-toggle"
          (click)="theme.toggle()"
          [attr.aria-label]="isDark() ? 'Switch to light mode' : 'Switch to dark mode'"
          [attr.title]="isDark() ? 'Switch to light mode' : 'Switch to dark mode'"
          class="flex h-7 w-7 cursor-pointer items-center justify-center rounded border border-edge text-muted transition-colors hover:border-accent hover:text-accent"
        >
          @if (isDark()) {
            <!-- sun -->
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <circle cx="12" cy="12" r="4" />
              <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
            </svg>
          } @else {
            <!-- moon -->
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          }
        </button>
        @if (user(); as u) {
          <div class="flex items-center gap-2 border-l border-edge pl-4">
            <span
              class="flex h-6 w-6 items-center justify-center rounded-full bg-primary/20 text-[11px] font-bold text-primary uppercase"
              aria-hidden="true"
              >{{ initial() }}</span
            >
            <span class="hidden max-w-32 truncate text-xs text-fg lg:inline" data-testid="user-name" [attr.title]="u.email ?? u.username">{{
              u.username
            }}</span>
            @if (u.authEnabled !== false) {
              <button
                type="button"
                data-testid="logout-button"
                (click)="logout()"
                class="rounded border border-edge px-2 py-1 text-[10px] font-semibold tracking-wider text-muted uppercase transition-colors hover:border-down hover:text-down"
              >
                Logout
              </button>
            }
          </div>
        }
      </div>
    </header>
  `,
})
export class HeaderComponent {
  private readonly portfolio = inject(PortfolioService);
  private readonly prices = inject(PriceStreamService);
  private readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);
  readonly isDark = computed(() => this.theme.theme() === 'dark');

  readonly user = this.auth.user;
  readonly initial = computed(() => (this.user()?.username ?? '?').charAt(0));

  constructor() {
    this.auth.load();
  }

  logout(): void {
    this.auth.logout();
  }

  readonly nav = [
    { id: 'markets', path: '/markets', label: 'Markets' },
    { id: 'watchlists', path: '/watchlists', label: 'Watchlists' },
    { id: 'portfolio', path: '/portfolio', label: 'Portfolio' },
    { id: 'alerts', path: '/alerts', label: 'Alerts' },
  ];

  readonly total = this.portfolio.total;
  readonly cash = this.portfolio.cash;
  readonly pnl = this.portfolio.unrealizedPnl;
  readonly status = this.prices.status;
  readonly pnlClass = computed(() => signClass(this.pnl()));
  readonly dotClass = computed(() => {
    switch (this.status()) {
      case 'connected':
        return 'bg-up';
      case 'reconnecting':
        return 'bg-accent';
      default:
        return 'bg-down';
    }
  });
}
