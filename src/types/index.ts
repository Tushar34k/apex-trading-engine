// ============================================
// Domain Types — matching backend PostgreSQL schema
// ============================================

// --- Auth & Users ---

export type AppRole = 'ADMIN' | 'USER' | 'VIEWER';

export interface User {
  id: string;
  email: string;
  roles: AppRole[];
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

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

// --- API Keys ---

export interface ApiKey {
  id: string;
  exchange: string;
  label: string;
  permissions: 'TRADE_ONLY' | 'READ_ONLY';
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
  permissions: 'TRADE_ONLY' | 'READ_ONLY';
}

export interface ApiKeyTestResult {
  valid: boolean;
  permissions: string[];
  futuresEnabled: boolean;
  spotEnabled: boolean;
  message: string;
}

// --- Strategies ---

export type StrategyType = 'TREND_FOLLOWING' | 'BREAKOUT' | 'PULLBACK';
export type MarketRegime = 'TRENDING' | 'RANGING' | 'HIGH_VOLATILITY';

export interface Strategy {
  id: string;
  name: string;
  description: string;
  version: string;
  type: StrategyType;
  isActive: boolean;
  createdAt: string;
}

export interface StrategyParameter {
  id: string;
  strategyId: string;
  paramKey: string;
  paramValue: string;
  paramType: 'INTEGER' | 'DOUBLE' | 'STRING' | 'BOOLEAN';
  description: string;
}

export interface StrategyPerformance {
  id: string;
  strategyId: string;
  totalReturn: number;
  winRate: number;
  sharpeRatio: number;
  maxDrawdown: number;
  profitFactor: number;
  totalTrades: number;
  calculatedAt: string;
}

// --- Trading Bots ---

export type BotMode = 'LIVE' | 'PAPER';
export type BotStatus = 'RUNNING' | 'PAUSED' | 'STOPPED';

export interface TradingBot {
  id: string;
  userId: string;
  strategyId: string;
  strategyName: string;
  strategyVersion: string;
  apiKeyId: string;
  symbol: string;
  timeframe: string;
  mode: BotMode;
  status: BotStatus;
  createdAt: string;
  startedAt: string | null;
  stoppedAt: string | null;
  pnl: number;
  totalTrades: number;
  winRate: number;
}

export interface CreateBotRequest {
  strategyId: string;
  apiKeyId: string;
  symbol: string;
  timeframe: string;
  mode: BotMode;
}

// --- Orders ---

export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'MARKET' | 'LIMIT';
export type OrderStatus = 'PENDING' | 'FILLED' | 'PARTIALLY_FILLED' | 'CANCELLED' | 'FAILED';

export interface Order {
  id: string;
  botId: string;
  userId: string;
  exchangeOrderId: string | null;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  price: number | null;
  filledQuantity: number;
  avgFillPrice: number | null;
  status: OrderStatus;
  slippage: number | null;
  retryCount: number;
  createdAt: string;
  filledAt: string | null;
}

// --- Trades ---

export type TradeSide = 'LONG' | 'SHORT';
export type TradeStatus = 'OPEN' | 'CLOSED';

export interface Trade {
  id: string;
  botId: string;
  userId: string;
  symbol: string;
  side: TradeSide;
  entryPrice: number;
  exitPrice: number | null;
  quantity: number;
  pnl: number | null;
  pnlPercent: number | null;
  stopLoss: number;
  takeProfit: number;
  status: TradeStatus;
  openedAt: string;
  closedAt: string | null;
  strategyName: string;
  strategyVersion: string;
}

export interface TradeExplanation {
  id: string;
  tradeId: string;
  strategyName: string;
  strategyVersion: string;
  marketRegime: MarketRegime;
  indicatorSnapshot: Record<string, number>;
  riskSettings: Record<string, number | string>;
  entryReason: string;
  exitReason: string | null;
  createdAt: string;
}

export interface TradeDetail extends Trade {
  explanation: TradeExplanation;
  entryOrder: Order;
  exitOrder: Order | null;
}

// --- Positions ---

export interface Position {
  id: string;
  symbol: string;
  side: TradeSide;
  size: number;
  entryPrice: number;
  currentPrice: number;
  pnl: number;
  pnlPercent: number;
  stopLoss: number;
  takeProfit: number;
  strategyName: string;
  botId: string;
}

// --- Risk ---

export type RiskLevel = 'TRADE' | 'DAILY' | 'PORTFOLIO';

export interface RiskConfigItem {
  id: string;
  configKey: string;
  configValue: string;
  level: RiskLevel;
}

export interface RiskStatus {
  totalExposure: number;
  maxExposure: number;
  dailyPnl: number;
  dailyLossLimit: number;
  currentDrawdown: number;
  maxDrawdown: number;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  allChecksPassed: boolean;
  violations: string[];
}

export interface ExposureBreakdown {
  symbol: string;
  exposure: number;
  exposurePercent: number;
  limit: number;
}

// --- Backtesting ---

export type BacktestStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface BacktestRun {
  id: string;
  strategyId: string;
  strategyName: string;
  strategyVersion: string;
  symbol: string;
  timeframe: string;
  startDate: string;
  endDate: string;
  initialBalance: number;
  finalBalance: number | null;
  totalReturn: number | null;
  winRate: number | null;
  maxDrawdown: number | null;
  sharpeRatio: number | null;
  profitFactor: number | null;
  totalTrades: number | null;
  status: BacktestStatus;
  createdAt: string;
  completedAt: string | null;
}

export interface RunBacktestRequest {
  strategyId: string;
  symbol: string;
  timeframe: string;
  startDate: string;
  endDate: string;
  initialBalance: number;
}

export interface BacktestTrade {
  id: string;
  backtestRunId: string;
  entryTime: string;
  exitTime: string;
  side: TradeSide;
  entryPrice: number;
  exitPrice: number;
  quantity: number;
  pnl: number;
  pnlPercent: number;
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

export interface MarketPrice {
  symbol: string;
  price: number;
  change24h: number;
  timestamp: number;
}

// --- Analytics ---

export interface PerformanceMetrics {
  totalReturn: number;
  totalReturnPercent: number;
  winRate: number;
  totalTrades: number;
  sharpeRatio: number;
  maxDrawdown: number;
  profitFactor: number;
  bestStrategy: string;
  bestStrategyReturn: number;
}

export interface EquityCurvePoint {
  timestamp: string;
  equity: number;
}

export interface MonthlyReturn {
  month: string;
  returnAmount: number;
  returnPercent: number;
}

export interface StrategyComparisonItem {
  strategyName: string;
  totalReturn: number;
  winRate: number;
  sharpeRatio: number;
}

// --- Admin ---

export interface AdminUser {
  id: string;
  email: string;
  roles: AppRole[];
  isActive: boolean;
  activeBots: number;
  lastLogin: string | null;
}

export interface SystemHealth {
  websocket: 'online' | 'offline' | 'degraded';
  exchangeApi: 'online' | 'offline' | 'degraded';
  database: 'online' | 'offline' | 'degraded';
  redis: 'online' | 'offline' | 'degraded';
  uptime: number;
  activeConnections: number;
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

export interface TradeOpenedEvent {
  type: 'TRADE_OPENED';
  trade: Trade;
  explanation: TradeExplanation;
}

export interface TradeClosedEvent {
  type: 'TRADE_CLOSED';
  trade: Trade;
  explanation: TradeExplanation;
}

export interface StopLossTriggeredEvent {
  type: 'STOP_LOSS_TRIGGERED';
  tradeId: string;
  symbol: string;
  triggerPrice: number;
  pnl: number;
}

export interface TakeProfitTriggeredEvent {
  type: 'TAKE_PROFIT_TRIGGERED';
  tradeId: string;
  symbol: string;
  triggerPrice: number;
  pnl: number;
}

export interface PositionUpdateEvent {
  type: 'POSITION_UPDATE';
  position: Position;
}

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

export interface NotificationEvent {
  type: 'NOTIFICATION';
  level: 'INFO' | 'WARNING' | 'ERROR' | 'CRITICAL';
  title: string;
  message: string;
  timestamp: string;
}

export type WebSocketEvent =
  | TradeOpenedEvent
  | TradeClosedEvent
  | StopLossTriggeredEvent
  | TakeProfitTriggeredEvent
  | PositionUpdateEvent
  | PriceUpdateEvent
  | OrderUpdateEvent
  | NotificationEvent;
