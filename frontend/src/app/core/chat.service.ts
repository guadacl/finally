import { Injectable, inject, signal } from '@angular/core';
import { ApiClient } from '../api/api-client';
import { ChatActions, ChatMessage, errorMessage } from '../api/models';
import { PortfolioService } from './portfolio.service';
import { WatchlistService } from './watchlist.service';

export function hasActions(a: ChatActions | null | undefined): boolean {
  return !!a && (a.trades.length > 0 || a.watchlistChanges.length > 0);
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly api = inject(ApiClient);
  private readonly portfolio = inject(PortfolioService);
  private readonly watchlist = inject(WatchlistService);
  private localId = 0;

  readonly messages = signal<ChatMessage[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  loadHistory(): void {
    this.api.getChatHistory().subscribe({
      next: (history) => {
        // Keep any optimistic messages sent before history arrived.
        this.messages.update((current) => [...history, ...current.filter((m) => m.id.startsWith('local-'))]);
      },
      error: () => {
        /* chat history is optional */
      },
    });
  }

  send(text: string): void {
    const message = text.trim();
    if (!message || this.loading()) return;
    this.error.set(null);
    this.messages.update((list) => [
      ...list,
      { id: `local-${++this.localId}`, role: 'user', content: message, createdAt: new Date().toISOString() },
    ]);
    this.loading.set(true);
    this.api.sendChatMessage({ message }).subscribe({
      next: (res) => {
        this.messages.update((list) => [
          ...list,
          { id: res.id, role: 'assistant', content: res.message, actions: res.actions, createdAt: res.createdAt },
        ]);
        this.loading.set(false);
        if (hasActions(res.actions)) {
          this.portfolio.load();
          this.portfolio.loadHistory();
          this.watchlist.load();
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'The assistant is unavailable right now'));
      },
    });
  }
}
