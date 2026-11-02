import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'markets' },
  {
    path: 'markets',
    title: 'Markets · FinAlly',
    loadComponent: () => import('./pages/markets/markets.page').then((m) => m.MarketsPage),
  },
  {
    path: 'watchlists',
    title: 'Watchlists · FinAlly',
    loadComponent: () => import('./pages/watchlists/watchlists.page').then((m) => m.WatchlistsPage),
  },
  {
    path: 'portfolio',
    title: 'Portfolio · FinAlly',
    loadComponent: () => import('./pages/portfolio/portfolio.page').then((m) => m.PortfolioPage),
  },
  { path: '**', redirectTo: 'markets' },
];
