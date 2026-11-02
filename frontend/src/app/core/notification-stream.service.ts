import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { SSE_NOTIFICATIONS_URL } from '../api/api-client';
import { Notification } from '../api/models';
import { ConnectionStatus, ES_CLOSED, EVENT_SOURCE_FACTORY, EventSourceLike } from './event-source';

export function parseNotification(data: string): Notification | null {
  try {
    const n = JSON.parse(data) as Partial<Notification>;
    if (typeof n.id !== 'string' || typeof n.type !== 'string' || typeof n.ticker !== 'string') {
      return null;
    }
    return n as Notification;
  } catch {
    return null;
  }
}

/** EventSource on `/api/stream/notifications` (event name `notification`). */
@Injectable({ providedIn: 'root' })
export class NotificationStreamService implements OnDestroy {
  private readonly factory = inject(EVENT_SOURCE_FACTORY);
  private source: EventSourceLike | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly subject = new Subject<Notification>();
  private readonly seen = new Set<string>();

  readonly notifications$: Observable<Notification> = this.subject.asObservable();
  readonly status = signal<ConnectionStatus>('disconnected');

  connect(): void {
    if (this.source) return;
    const es = this.factory(SSE_NOTIFICATIONS_URL);
    this.source = es;
    this.status.set('reconnecting');
    es.onopen = () => this.status.set('connected');
    es.onerror = () => {
      if (es.readyState === ES_CLOSED) {
        this.status.set('disconnected');
        es.close();
        if (this.source === es) this.source = null;
        this.retryTimer = setTimeout(() => this.connect(), 5000);
      } else {
        this.status.set('reconnecting');
      }
    };
    es.addEventListener('notification', (ev: MessageEvent) => {
      const n = parseNotification(String(ev.data));
      if (!n || this.seen.has(n.id)) return; // de-dupe (Camunda may retry)
      this.seen.add(n.id);
      if (this.seen.size > 500) this.seen.clear();
      this.subject.next(n);
    });
  }

  disconnect(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.source?.close();
    this.source = null;
    this.status.set('disconnected');
  }

  ngOnDestroy(): void {
    this.disconnect();
    this.subject.complete();
  }
}
