import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FLASH_MS, PriceFlashDirective } from './price-flash.directive';

@Component({
  imports: [PriceFlashDirective],
  template: `<span id="p" [appPriceFlash]="price()">{{ price() }}</span>`,
})
class HostComponent {
  price = signal<number | null>(null);
}

describe('PriceFlashDirective', () => {
  afterEach(() => vi.useRealTimers());

  it('flashes green on uptick, red on downtick, then clears', async () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(HostComponent);
    const el: HTMLElement = fixture.nativeElement.querySelector('#p');

    fixture.componentInstance.price.set(100);
    fixture.detectChanges();
    expect(el.classList.contains('flash-up')).toBe(false); // first value: no flash

    fixture.componentInstance.price.set(101);
    fixture.detectChanges();
    expect(el.classList.contains('flash-up')).toBe(true);

    fixture.componentInstance.price.set(99);
    fixture.detectChanges();
    expect(el.classList.contains('flash-down')).toBe(true);
    expect(el.classList.contains('flash-up')).toBe(false);

    vi.advanceTimersByTime(FLASH_MS + 10);
    expect(el.classList.contains('flash-down')).toBe(false);
  });

  it('does not flash when the price is unchanged', () => {
    const fixture = TestBed.createComponent(HostComponent);
    const el: HTMLElement = fixture.nativeElement.querySelector('#p');
    fixture.componentInstance.price.set(50);
    fixture.detectChanges();
    fixture.componentInstance.price.set(50);
    fixture.detectChanges();
    expect(el.className).toBe('');
  });
});
