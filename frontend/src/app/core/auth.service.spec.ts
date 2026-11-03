import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService, LOGIN_URL, PAGE_NAVIGATOR, authInterceptor } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let client: HttpClient;
  const navigator = { assign: vi.fn() };

  beforeEach(() => {
    navigator.assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: PAGE_NAVIGATOR, useValue: navigator },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => http.verify());

  it('loads the current user', () => {
    auth.load();
    http.expectOne('/api/me').flush({ id: 'kc-1', username: 'alice', email: 'a@x.io', authEnabled: true });
    expect(auth.user()?.username).toBe('alice');
  });

  it('redirects to the Keycloak login once when the API answers 401', () => {
    client.get('/api/portfolio').subscribe({ error: () => undefined });
    client.get('/api/watchlist').subscribe({ error: () => undefined });
    http.expectOne('/api/portfolio').flush({ error: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/watchlist').flush({ error: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    expect(navigator.assign).toHaveBeenCalledTimes(1);
    expect(navigator.assign).toHaveBeenCalledWith(LOGIN_URL);
  });

  it('does not redirect on other errors', () => {
    client.post('/api/portfolio/trade', {}).subscribe({ error: () => undefined });
    http.expectOne('/api/portfolio/trade').flush({ error: 'INSUFFICIENT_CASH' }, { status: 400, statusText: 'Bad Request' });
    expect(navigator.assign).not.toHaveBeenCalled();
  });

  it('logs out with a plain POST /logout form', () => {
    const doc = TestBed.inject(DOCUMENT);
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => undefined);
    auth.logout();
    const form = doc.querySelector('form[action="/logout"]') as HTMLFormElement;
    expect(form.method.toLowerCase()).toBe('post');
    expect(submit).toHaveBeenCalled();
    form.remove();
    submit.mockRestore();
  });
});
