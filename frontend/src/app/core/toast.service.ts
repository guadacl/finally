import { Injectable, OnDestroy, signal } from '@angular/core';
import { KanbanColumn, NotificationType } from '../api/models';

export interface Toast {
  id: number;
  type: NotificationType | 'INFO' | 'ERROR';
  ticker?: string;
  column?: KanbanColumn;
  targetPrice?: number;
  price?: number | null;
  message: string;
}

export const TOAST_DURATION_MS = 6000;
const MAX_TOASTS = 6;

@Injectable({ providedIn: 'root' })
export class ToastService implements OnDestroy {
  private nextId = 1;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();
  readonly toasts = signal<Toast[]>([]);

  show(toast: Omit<Toast, 'id'>, durationMs = TOAST_DURATION_MS): number {
    const id = this.nextId++;
    this.toasts.update((list) => [...list, { ...toast, id }].slice(-MAX_TOASTS));
    this.timers.set(
      id,
      setTimeout(() => this.dismiss(id), durationMs),
    );
    return id;
  }

  dismiss(id: number): void {
    const t = this.timers.get(id);
    if (t) clearTimeout(t);
    this.timers.delete(id);
    this.toasts.update((list) => list.filter((x) => x.id !== id));
  }

  ngOnDestroy(): void {
    this.timers.forEach((t) => clearTimeout(t));
    this.timers.clear();
  }
}
