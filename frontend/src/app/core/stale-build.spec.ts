import { TestBed } from '@angular/core/testing';
import { NavigationError } from '@angular/router';
import { vi } from 'vitest';
import { PAGE_NAVIGATOR } from './auth.service';
import { isStaleChunkError, reloadOnStaleBuild } from './stale-build';

describe('stale build reload', () => {
  const navigator = { assign: vi.fn() };

  beforeEach(() => {
    navigator.assign.mockReset();
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [{ provide: PAGE_NAVIGATOR, useValue: navigator }] });
  });

  const failNav = (error: unknown) => new NavigationError(1, '/alerts', error);

  it('recognizes missing-chunk errors from different browsers', () => {
    expect(isStaleChunkError(new TypeError('Failed to fetch dynamically imported module: http://localhost:8000/chunk-OLD.js'))).toBe(true);
    expect(isStaleChunkError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isStaleChunkError(new Error('error loading dynamically imported module'))).toBe(true);
    expect(isStaleChunkError(new Error('Cannot read properties of undefined'))).toBe(false);
    expect(isStaleChunkError(null)).toBe(false);
  });

  it('reloads the requested page once, then does not loop', () => {
    const err = new TypeError('Failed to fetch dynamically imported module: /chunk-OLD.js');
    TestBed.runInInjectionContext(() => reloadOnStaleBuild(failNav(err)));
    TestBed.runInInjectionContext(() => reloadOnStaleBuild(failNav(err)));
    expect(navigator.assign).toHaveBeenCalledTimes(1);
    expect(navigator.assign).toHaveBeenCalledWith('/alerts');
  });

  it('ignores other navigation errors', () => {
    TestBed.runInInjectionContext(() => reloadOnStaleBuild(failNav(new Error('some bug'))));
    expect(navigator.assign).not.toHaveBeenCalled();
  });
});
