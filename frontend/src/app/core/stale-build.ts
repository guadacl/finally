import { DOCUMENT } from '@angular/common';
import { inject } from '@angular/core';
import { NavigationError } from '@angular/router';
import { PAGE_NAVIGATOR } from './auth.service';

/**
 * A tab opened before the app was rebuilt still references the old, hashed page chunks; after the update those
 * files are gone (404) and navigating to a lazily loaded page fails. Detect that and reload once, so the browser
 * picks up the new build. A short guard prevents reload loops if the server itself is broken.
 */
const STALE_CHUNK = /dynamically imported module|Importing a module script failed|Loading chunk|ChunkLoadError|error loading dynamically imported/i;
const GUARD_KEY = 'finally.staleBuildReloadAt';
const GUARD_MS = 30_000;

export function isStaleChunkError(error: unknown): boolean {
  const text = error instanceof Error ? `${error.name} ${error.message}` : String(error ?? '');
  return STALE_CHUNK.test(text);
}

/** Router navigation error handler (withNavigationErrorHandler). */
export function reloadOnStaleBuild(event: NavigationError): void {
  if (!isStaleChunkError(event.error)) return;
  const doc = inject(DOCUMENT);
  const navigator = inject(PAGE_NAVIGATOR);
  const storage = doc.defaultView?.sessionStorage;
  try {
    const last = Number(storage?.getItem(GUARD_KEY) ?? 0);
    if (Date.now() - last < GUARD_MS) return; // already reloaded just now: don't loop
    storage?.setItem(GUARD_KEY, String(Date.now()));
  } catch {
    // storage unavailable: still reload once
  }
  navigator.assign(event.url); // full page load of the page the user wanted, with the new build
}
