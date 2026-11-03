import { provideHttpClient, withFetch, withInterceptors, withNoXsrfProtection } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withNavigationErrorHandler } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.service';
import { reloadOnStaleBuild } from './core/stale-build';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideHttpClient(
      withFetch(),
      // The backend needs no CSRF token: a logged-in user can use every feature (SameSite session cookie).
      withNoXsrfProtection(),
      withInterceptors([authInterceptor]),
    ),
    // after an update, an open tab reloads itself instead of failing to load the new pages
    provideRouter(routes, withComponentInputBinding(), withNavigationErrorHandler(reloadOnStaleBuild)),
  ],
};
