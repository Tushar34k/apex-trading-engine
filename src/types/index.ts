// ============================================
// Domain Types — V1 Production Trading Bot
// ============================================

// --- Auth ---

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface User {
  id: string;
  email: string;
  roles: string[];
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

// --- API Keys ---

export interface ApiKey {
  id: string;
  exchange: string;
  label: string;
  permissions: string;
  isActive: boolean;
  createdAt: string;
  lastUsedAt: string | null;
  maskedKey: string;
}

export interface AddApiKeyRequest {
  exchange: string;
  label: string;
  apiKey: string;
  apiSecret: string;
  permissions: string;
}

export interface ApiKeyTestResult {
  valid: boolean;
  message: string;
}

// --- Trading Bots ---

export type BotStatus = 'RUNNING' | 'STOPPED';

export interface TradingBot {
  id: string;
  userId: string;
  name: string;
  symbol: string;
  timeframe: string;
  strategyType: string;
  fastEma: number;
  slowEma: number;
  tradeSizePercent: number;
  status: BotStatus;
  hasOpenPosition: boolean;
  entryPrice: number | null;
  quantity: number | null;
  startedAt: string | null;
  stoppedAt: string | null;
  lastTradeTime: string | null;
  isProcessing: boolean;
  createdAt: string;
  // Computed fields from API
  pnl?: number;
  totalTrades?: number;
  winRate?: number;
}

export interface CreateBotRequest {
  name: string;
  symbol: string;
  timeframe: string;
  strategyType: string;
  fastEma: number;
  slowEma: number;
  tradeSizePercent: number;
  apiKeyId: string;
}

// --- Trades ---

export interface Trade {
  id: string;
  botId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  entryPrice: number;
  exitPrice: number | null;
  quantity: number;
  pnl: number | null;
  openedAt: string;
  closedAt: string | null;
}

// --- Orders ---

export interface Order {
  id: string;
  botId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  type: string;
  quantity: number;
  price: number | null;
  filledQuantity: number;
  avgFillPrice: number | null;
  status: string;
  createdAt: string;
  filledAt: string | null;
  exchangeOrderId: string | null;
}

// --- Positions ---

export interface Position {
  id: string;
  symbol: string;
  side: string;
  size: number;
  entryPrice: number;
  currentPrice: number;
  pnl: number;
  pnlPercent: number;
  botId: string;
}

// --- Market Data ---

export interface CandleData {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

// --- Balances ---

export interface Balance {
  asset: string;
  free: number;
  locked: number;
  total: number;
  usdValue: number;
}

// --- WebSocket Events ---

export interface PriceUpdateEvent {
  type: 'PRICE_UPDATE';
  symbol: string;
  price: number;
  change24h: number;
}

export interface OrderUpdateEvent {
  type: 'ORDER_UPDATE';
  order: Order;
}

export interface TradeOpenedEvent {
  type: 'TRADE_OPENED';
  trade: Trade;
}

export interface TradeClosedEvent {
  type: 'TRADE_CLOSED';
  trade: Trade;
}

export interface PositionUpdateEvent {
  type: 'POSITION_UPDATE';
  position: Position;
}

export type WebSocketEvent =
  | PriceUpdateEvent
  | OrderUpdateEvent
  | TradeOpenedEvent
  | TradeClosedEvent
  | PositionUpdateEvent;
