/**
 * Typed models mirroring `backend/openapi/openapi.yaml` (components.schemas).
 * Keep in sync with the spec — field names and optionality match exactly.
 */

export interface ApiError {
  /** Machine-readable code, e.g. INSUFFICIENT_CASH */
  error: string;
  message: string;
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
export const KANBAN_COLUMNS: readonly KanbanColumn[] = ['SUPPORT', 'RESISTANCE'];

/**
 * User-facing column names. The API codes stay SUPPORT / RESISTANCE:
 * a Stop Loss alerts when the price falls to the target, a Take Profit when it rises to it.
 */
export const COLUMN_LABEL: Readonly<Record<KanbanColumn, string>> = {
  SUPPORT: 'Stop Loss',
  RESISTANCE: 'Take Profit',
};

export interface KanbanCard {
  id: string;
  ticker: string;
  column: KanbanColumn;
  targetPrice: number;
  sortOrder: number;
  /** false after an alert fired, until the price crosses back */
  armed: boolean;
  currentPrice?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface KanbanCardCreate {
  ticker: string;
  column: KanbanColumn;
  targetPrice: number;
}

export interface KanbanCardUpdate {
  column?: KanbanColumn;
  targetPrice?: number;
  sortOrder?: number;
}

export type NotificationType = 'CARD_CREATED' | 'CARD_MOVED' | 'TARGET_HIT';

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
