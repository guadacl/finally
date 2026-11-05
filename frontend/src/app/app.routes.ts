import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'markets' },
  {
    path: 'markets',
    title: 'Markets · Tradex',
    loadComponent: () => import('./pages/markets/markets.page').then((m) => m.MarketsPage),
  },
  {
    path: 'watchlists',
    title: 'Watchlists · Tradex',
    loadComponent: () => import('./pages/watchlists/watchlists.page').then((m) => m.WatchlistsPage),
  },
  {
    path: 'portfolio',
    title: 'Portfolio · Tradex',
    loadComponent: () => import('./pages/portfolio/portfolio.page').then((m) => m.PortfolioPage),
  },
  {
    path: 'alerts',
    title: 'Alerts · Tradex',
    loadComponent: () => import('./pages/alerts/alerts.page').then((m) => m.AlertsPage),
  },
  { path: '**', redirectTo: 'markets' },
];
