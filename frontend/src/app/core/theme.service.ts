import { Injectable, effect, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'finally-theme';

function readStored(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

/** Dark (default) / light UI theme, applied as `data-theme` on <html> and remembered per browser. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>(readStored());

  constructor() {
    effect(() => {
      const t = this.theme();
      const root = document.documentElement;
      root.setAttribute('data-theme', t);
      root.style.colorScheme = t;
      try {
        localStorage.setItem(STORAGE_KEY, t);
      } catch {
        /* storage unavailable: theme still applies for this session */
      }
    });
  }

  toggle(): void {
    this.theme.update((t) => (t === 'dark' ? 'light' : 'dark'));
  }
}
