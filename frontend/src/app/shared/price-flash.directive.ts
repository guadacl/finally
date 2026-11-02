import { Directive, ElementRef, OnDestroy, effect, inject, input } from '@angular/core';

export const FLASH_MS = 500;

/**
 * Briefly flashes the host green (uptick) or red (downtick) whenever the bound price changes.
 * Usage: `<span [appPriceFlash]="price">`.
 */
@Directive({ selector: '[appPriceFlash]' })
export class PriceFlashDirective implements OnDestroy {
  readonly appPriceFlash = input<number | null | undefined>(null);

  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private last: number | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const price = this.appPriceFlash();
      if (price == null) return;
      const prev = this.last;
      this.last = price;
      if (prev == null || price === prev) return;
      this.flash(price > prev ? 'flash-up' : 'flash-down');
    });
  }

  private flash(cls: 'flash-up' | 'flash-down'): void {
    this.el.classList.remove('flash-up', 'flash-down');
    void this.el.offsetWidth; // restart the CSS animation
    this.el.classList.add(cls);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.el.classList.remove('flash-up', 'flash-down');
      this.timer = null;
    }, FLASH_MS);
  }

  ngOnDestroy(): void {
    if (this.timer) clearTimeout(this.timer);
  }
}
