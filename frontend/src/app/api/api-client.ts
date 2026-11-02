import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ChatMessage,
  ChatRequest,
  ChatResponse,
  Health,
  KanbanCard,
  KanbanCardCreate,
  KanbanCardUpdate,
  Portfolio,
  PortfolioSnapshot,
  TradeRequest,
  TradeResult,
  WatchlistAddRequest,
  WatchlistItem,
} from './models';

/** SSE endpoint paths (consumed with EventSource, not HttpClient). */
export const SSE_PRICES_URL = '/api/stream/prices';
export const SSE_NOTIFICATIONS_URL = '/api/stream/notifications';

/**
 * Hand-written typed REST client for `backend/openapi/openapi.yaml`.
 * One method per operationId; all calls are same-origin `/api/*`.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);

  // system
  getHealth(): Observable<Health> {
    return this.http.get<Health>('/api/health');
  }

  // portfolio
  getPortfolio(): Observable<Portfolio> {
    return this.http.get<Portfolio>('/api/portfolio');
  }
  executeTrade(body: TradeRequest): Observable<TradeResult> {
    return this.http.post<TradeResult>('/api/portfolio/trade', body);
  }
  getPortfolioHistory(): Observable<PortfolioSnapshot[]> {
    return this.http.get<PortfolioSnapshot[]>('/api/portfolio/history');
  }

  // watchlist
  getWatchlist(): Observable<WatchlistItem[]> {
    return this.http.get<WatchlistItem[]>('/api/watchlist');
  }
  addToWatchlist(body: WatchlistAddRequest): Observable<WatchlistItem> {
    return this.http.post<WatchlistItem>('/api/watchlist', body);
  }
  removeFromWatchlist(ticker: string): Observable<void> {
    return this.http.delete<void>(`/api/watchlist/${encodeURIComponent(ticker)}`);
  }

  // chat
  sendChatMessage(body: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', body);
  }
  getChatHistory(): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>('/api/chat/history');
  }

  // kanban
  getKanbanCards(): Observable<KanbanCard[]> {
    return this.http.get<KanbanCard[]>('/api/kanban');
  }
  createKanbanCard(body: KanbanCardCreate): Observable<KanbanCard> {
    return this.http.post<KanbanCard>('/api/kanban', body);
  }
  updateKanbanCard(id: string, body: KanbanCardUpdate): Observable<KanbanCard> {
    return this.http.patch<KanbanCard>(`/api/kanban/${encodeURIComponent(id)}`, body);
  }
  deleteKanbanCard(id: string): Observable<void> {
    return this.http.delete<void>(`/api/kanban/${encodeURIComponent(id)}`);
  }
}
