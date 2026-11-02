import { ES_CLOSED, ES_CONNECTING, ES_OPEN, EventSourceFactory, EventSourceLike } from '../app/core/event-source';

/** Controllable EventSource stand-in for unit tests. */
export class FakeEventSource implements EventSourceLike {
  static instances: FakeEventSource[] = [];
  readyState = ES_CONNECTING;
  onopen: ((ev: Event) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  closed = false;
  private listeners = new Map<string, ((ev: MessageEvent) => void)[]>();

  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
    this.readyState = ES_CLOSED;
  }

  open(): void {
    this.readyState = ES_OPEN;
    this.onopen?.(new Event('open'));
  }

  fail(readyState: number): void {
    this.readyState = readyState;
    this.onerror?.(new Event('error'));
  }

  emit(type: string, data: unknown): void {
    const ev = { data: typeof data === 'string' ? data : JSON.stringify(data) } as MessageEvent;
    (this.listeners.get(type) ?? []).forEach((l) => l(ev));
  }

  static reset(): void {
    FakeEventSource.instances = [];
  }

  static last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }
}

export const fakeEventSourceFactory: EventSourceFactory = (url) => new FakeEventSource(url);
