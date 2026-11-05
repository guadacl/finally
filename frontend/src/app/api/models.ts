/**
 * Typed models mirroring `backend/openapi/openapi.yaml` (components.schemas).
 * Keep in sync with the spec — field names and optionality match exactly.
 */

export interface ApiError {
  /** Machine-readable code, e.g. INSUFFICIENT_CASH */
  error: string;
  message: string;
}

/** Alerts page: a watchlist ticker with an upper and/or lower price limit and a comment. */
export interface PriceAlert {
  id: string;
  ticker: string;
  upperLimit?: number | null;
  lowerLimit?: number | null;
  comment?: string | null;
  currentPrice?: number | null;
  /** Today's high/low (Finnhub quote; simulator: since server start). */
  dayHigh?: number | null;
  dayLow?: number | null;
  /** Shares traded in the last completed session (Massive end-of-day; null without a Massive key). */
  lastSessionVolume?: number | null;
  /** Trading day of lastSessionVolume, e.g. "2026-11-02". */
  lastSessionDate?: string | null;
  /** RSI(14) on daily closes, live price as today's close during the session (null without a Massive key). */
  rsi?: number | null;
  createdAt: string;
}

export interface PriceAlertCreate {
  ticker: string;
  upperLimit?: number | null;
  lowerLimit?: number | null;
  comment?: string | null;
}

/** `GET /api/me` — the logged-in Keycloak user. */
export interface UserInfo {
  id: string;
  username: string;
  email?: string | null;
  name?: string | null;
  authEnabled?: boolean;
}

export interface Health {
  status: string;
  database?: string;
  workflow?: string;
  marketData?: string;
}

export type Direction = 'up' | 'down' | 'flat';

export interface PriceUpdate {
  ticker: string;
  price: number;
  previousPrice: number;
  timestamp: string;
  direction: Direction;
}

export type TradeSide = 'buy' | 'sell';

export interface TradeRequest {
  ticker: string;
  quantity: number;
  side: TradeSide;
}

export interface Trade {
  id: string;
  ticker: string;
  side: TradeSide;
  quantity: number;
  price: number;
  executedAt: string;
}

export interface TradeResult {
  trade: Trade;
  portfolio: Portfolio;
}

export interface Position {
  ticker: string;
  quantity: number;
  avgCost: number;
  currentPrice: number;
  marketValue: number;
  unrealizedPnl: number;
  /** e.g. 5.25 means +5.25% */
  unrealizedPnlPercent: number;
}

export interface Portfolio {
  cashBalance: number;
  positionsValue: number;
  totalValue: number;
  unrealizedPnl: number;
  positions: Position[];
}

export interface PortfolioSnapshot {
  totalValue: number;
  recordedAt: string;
}

export interface WatchlistAddRequest {
  ticker: string;
}

export interface CompanyName {
  ticker: string;
  name?: string | null;
}

export interface WatchlistItem {
  ticker: string;
  price?: number | null;
  previousPrice?: number | null;
  sessionOpenPrice?: number | null;
  changePercent?: number | null;
  addedAt: string;
}

export interface ChatRequest {
  message: string;
}

export type ActionStatus = 'executed' | 'failed';

export interface ExecutedTrade {
  ticker: string;
  side: TradeSide;
  quantity: number;
  price?: number | null;
  status: ActionStatus;
  error?: string | null;
}

export type WatchlistAction = 'add' | 'remove';

export interface ExecutedWatchlistChange {
  ticker: string;
  action: WatchlistAction;
  status: ActionStatus;
  error?: string | null;
}

export interface ChatActions {
  trades: ExecutedTrade[];
  watchlistChanges: ExecutedWatchlistChange[];
}

export interface ChatResponse {
  id: string;
  message: string;
  actions: ChatActions;
  createdAt: string;
}

export type ChatRole = 'user' | 'assistant';

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  actions?: ChatActions | null;
  createdAt: string;
}

export type KanbanColumn = 'SUPPORT' | 'RESISTANCE';
export type KanbanCardStatus = 'ACTIVE' | 'NOTIFIED';
export const KANBAN_COLUMNS: readonly KanbanColumn[] = ['SUPPORT', 'RESISTANCE'];

/**
 * User-facing column names. The API codes stay SUPPORT / RESISTANCE:
 * a Support card alerts when the price falls to the target, a Resistance card when it rises to it.
 */
export const COLUMN_LABEL: Readonly<Record<KanbanColumn, string>> = {
  SUPPORT: 'Support',
  RESISTANCE: 'Resistance',
};

export interface KanbanCard {
  id: string;
  ticker: string;
  column: KanbanColumn;
  targetPrice: number;
  sortOrder: number;
  /** false once the alert fired (NOTIFIED); true again only after the target is edited or the card is moved */
  armed: boolean;
  /** ACTIVE until the target is first reached, then NOTIFIED (alert sent once). Mirrors the Camunda process. */
  status?: KanbanCardStatus;
  currentPrice?: number | null;
  /** Free-text note, editable on the card. */
  comment?: string | null;
  /** Same market columns as PriceAlert (Alerts page tables). */
  dayHigh?: number | null;
  dayLow?: number | null;
  lastSessionVolume?: number | null;
  lastSessionDate?: string | null;
  rsi?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface KanbanCardCreate {
  ticker: string;
  column: KanbanColumn;
  targetPrice: number;
  comment?: string | null;
}

export interface KanbanCardUpdate {
  column?: KanbanColumn;
  targetPrice?: number;
  sortOrder?: number;
  /** Absent = unchanged; '' = cleared. Does not re-arm the card. */
  comment?: string;
}

export type NotificationType = 'CARD_CREATED' | 'CARD_MOVED' | 'CARD_DELETED' | 'TARGET_HIT';

export interface Notification {
  id: string;
  type: NotificationType;
  cardId: string;
  ticker: string;
  column: KanbanColumn;
  targetPrice: number;
  price?: number | null;
  message: string;
  createdAt: string;
}

/** Extract a human-readable message from an HttpErrorResponse-like value. */
export function errorMessage(err: unknown, fallback = 'Request failed'): string {
  const e = err as { error?: unknown; message?: string; status?: number } | null;
  const body = e?.error as Partial<ApiError> | string | null | undefined;
  if (body && typeof body === 'object' && typeof body.message === 'string' && body.message) {
    return body.message;
  }
  if (typeof body === 'string' && body.trim()) {
    try {
      const parsed = JSON.parse(body) as Partial<ApiError>;
      if (parsed.message) return parsed.message;
    } catch {
      /* not JSON */
    }
  }
  if (e?.status === 0) return 'Backend unreachable';
  return fallback;
}
