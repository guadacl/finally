import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { UserInfo } from '../api/models';

/**
 * Keycloak login is handled by the backend ("backend for frontend"): the session lives in an HttpOnly
 * cookie and the SPA never sees tokens. The SPA only needs to:
 * - send the user to the login page when the API answers 401 (session expired),
 * - show who is logged in (`GET /api/me`),
 * - log out with a `POST /logout` (a plain form post; no token needed).
 */
export const LOGIN_URL = '/oauth2/authorization/keycloak';
export const LOGOUT_URL = '/logout';

/** Full-page navigation, injectable so tests don't navigate the test runner. */
export const PAGE_NAVIGATOR = new InjectionToken<{ assign(url: string): void }>('PAGE_NAVIGATOR', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).location,
});

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiClient);
  private readonly doc = inject(DOCUMENT);
  private readonly navigator = inject(PAGE_NAVIGATOR);
  private redirecting = false;

  readonly user = signal<UserInfo | null>(null);

  load(): void {
    this.api.getCurrentUser().subscribe({
      next: (u) => this.user.set(u),
      error: () => this.user.set(null),
    });
  }

  /** Sends the browser to Keycloak's login page (which also offers "Register"). Only once per page. */
  login(): void {
    if (this.redirecting) {
      return;
    }
    this.redirecting = true;
    this.navigator.assign(LOGIN_URL);
  }

  /** Ends the app session and the Keycloak session, then Keycloak redirects back to the app (login page). */
  logout(): void {
    const form = this.doc.createElement('form');
    form.method = 'POST';
    form.action = LOGOUT_URL;
    form.style.display = 'none';
    this.doc.body.appendChild(form);
    form.submit();
  }
}

/** On any 401 from `/api/*`, go to the login page (the session expired or was ended elsewhere). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && req.url.startsWith('/api/')) {
        auth.login();
      }
      return throwError(() => err);
    }),
  );
};
