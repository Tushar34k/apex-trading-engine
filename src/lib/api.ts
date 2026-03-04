import axios, { AxiosInstance, AxiosError, InternalAxiosRequestConfig } from 'axios';
import type {
  AuthTokens, LoginRequest, RegisterRequest, User,
  ApiKey, AddApiKeyRequest, ApiKeyTestResult,
  Strategy, StrategyParameter, StrategyPerformance,
  TradingBot, CreateBotRequest,
  Order, Trade, TradeDetail, Position,
  RiskConfigItem, RiskStatus, ExposureBreakdown,
  BacktestRun, RunBacktestRequest, BacktestTrade,
  CandleData,
  PerformanceMetrics, EquityCurvePoint, MonthlyReturn, StrategyComparisonItem,
  AdminUser, SystemHealth, Balance,
} from '@/types';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

let accessToken: string | null = null;
let refreshToken: string | null = null;
let onTokenRefreshFailed: (() => void) | null = null;

export function setAuthTokens(tokens: AuthTokens) {
  accessToken = tokens.accessToken;
  refreshToken = tokens.refreshToken;
}

export function clearAuthTokens() {
  accessToken = null;
  refreshToken = null;
}

export function getAccessToken() {
  return accessToken;
}

export function setOnTokenRefreshFailed(cb: () => void) {
  onTokenRefreshFailed = cb;
}

const client: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
});

// Request interceptor: inject JWT
client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken && config.headers) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

// Response interceptor: auto-refresh on 401
let isRefreshing = false;
let failedQueue: Array<{ resolve: (token: string) => void; reject: (err: unknown) => void }> = [];

function processQueue(error: unknown, token: string | null) {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error);
    else resolve(token!);
  });
  failedQueue = [];
}

client.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };

    if (error.response?.status === 401 && !originalRequest._retry && refreshToken) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({
            resolve: (token: string) => {
              originalRequest.headers.Authorization = `Bearer ${token}`;
              resolve(client(originalRequest));
            },
            reject,
          });
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const { data } = await axios.post<AuthTokens>(`${BASE_URL}/auth/refresh`, {
          refreshToken,
        });
        setAuthTokens(data);
        processQueue(null, data.accessToken);
        originalRequest.headers.Authorization = `Bearer ${data.accessToken}`;
        return client(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        clearAuthTokens();
        onTokenRefreshFailed?.();
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

// ============================================
// API Methods
// ============================================

// --- Auth ---
export const auth = {
  login: (data: LoginRequest) =>
    client.post<AuthTokens>('/auth/login', data).then((r) => r.data),
  register: (data: RegisterRequest) =>
    client.post<AuthTokens>('/auth/register', data).then((r) => r.data),
  refresh: (token: string) =>
    client.post<AuthTokens>('/auth/refresh', { refreshToken: token }).then((r) => r.data),
};

// --- Users ---
export const users = {
  me: () => client.get<User>('/users/me').then((r) => r.data),
  update: (data: Partial<User>) => client.put<User>('/users/me', data).then((r) => r.data),
};

// --- API Keys ---
export const apiKeys = {
  list: () => client.get<ApiKey[]>('/api-keys').then((r) => r.data),
  add: (data: AddApiKeyRequest) => client.post<ApiKey>('/api-keys', data).then((r) => r.data),
  delete: (id: string) => client.delete(`/api-keys/${id}`),
  test: (id: string) => client.post<ApiKeyTestResult>(`/api-keys/${id}/test`).then((r) => r.data),
};

// --- Strategies ---
export const strategies = {
  list: () => client.get<Strategy[]>('/strategies').then((r) => r.data),
  get: (id: string) => client.get<Strategy>(`/strategies/${id}`).then((r) => r.data),
  create: (data: Partial<Strategy>) => client.post<Strategy>('/strategies', data).then((r) => r.data),
  update: (id: string, data: Partial<Strategy>) => client.put<Strategy>(`/strategies/${id}`, data).then((r) => r.data),
  getParams: (id: string) => client.get<StrategyParameter[]>(`/strategies/${id}/parameters`).then((r) => r.data),
  updateParams: (id: string, params: Partial<StrategyParameter>[]) =>
    client.put<StrategyParameter[]>(`/strategies/${id}/parameters`, params).then((r) => r.data),
  getPerformance: (id: string) =>
    client.get<StrategyPerformance>(`/strategies/${id}/performance`).then((r) => r.data),
};

// --- Bots ---
export const bots = {
  list: () => client.get<TradingBot[]>('/bots').then((r) => r.data),
  create: (data: CreateBotRequest) => client.post<TradingBot>('/bots', data).then((r) => r.data),
  start: (id: string) => client.post<TradingBot>(`/bots/${id}/start`).then((r) => r.data),
  stop: (id: string) => client.post<TradingBot>(`/bots/${id}/stop`).then((r) => r.data),
  pause: (id: string) => client.post<TradingBot>(`/bots/${id}/pause`).then((r) => r.data),
  delete: (id: string) => client.delete(`/bots/${id}`),
};

// --- Orders & Trades ---
export const orders = {
  list: (params?: { botId?: string; status?: string; symbol?: string }) =>
    client.get<Order[]>('/orders', { params }).then((r) => r.data),
};

export const trades = {
  list: (params?: { botId?: string; status?: string; symbol?: string; mode?: string }) =>
    client.get<Trade[]>('/trades', { params }).then((r) => r.data),
  get: (id: string) => client.get<TradeDetail>(`/trades/${id}`).then((r) => r.data),
};

export const positions = {
  list: () => client.get<Position[]>('/positions').then((r) => r.data),
};

// --- Risk ---
export const risk = {
  getConfig: () => client.get<RiskConfigItem[]>('/risk/config').then((r) => r.data),
  updateConfig: (items: Partial<RiskConfigItem>[]) =>
    client.put<RiskConfigItem[]>('/risk/config', items).then((r) => r.data),
  getStatus: () => client.get<RiskStatus>('/risk/status').then((r) => r.data),
  getExposure: () => client.get<ExposureBreakdown[]>('/risk/exposure').then((r) => r.data),
};

// --- Backtesting ---
export const backtest = {
  run: (data: RunBacktestRequest) => client.post<BacktestRun>('/backtest/run', data).then((r) => r.data),
  listResults: () => client.get<BacktestRun[]>('/backtest/results').then((r) => r.data),
  get: (id: string) => client.get<BacktestRun>(`/backtest/${id}`).then((r) => r.data),
  getTrades: (id: string) => client.get<BacktestTrade[]>(`/backtest/${id}/trades`).then((r) => r.data),
};

// --- Market Data ---
export const market = {
  getCandles: (symbol: string, timeframe: string, limit?: number) =>
    client.get<CandleData[]>('/market/candles', { params: { symbol, timeframe, limit } }).then((r) => r.data),
};

// --- Analytics ---
export const analytics = {
  getPerformance: () => client.get<PerformanceMetrics>('/analytics/performance').then((r) => r.data),
  getEquityCurve: () => client.get<EquityCurvePoint[]>('/analytics/equity-curve').then((r) => r.data),
  getMonthlyReturns: () => client.get<MonthlyReturn[]>('/analytics/monthly-returns').then((r) => r.data),
  getStrategyComparison: () =>
    client.get<StrategyComparisonItem[]>('/analytics/strategy-comparison').then((r) => r.data),
};

// --- Admin ---
export const admin = {
  listUsers: () => client.get<AdminUser[]>('/admin/users').then((r) => r.data),
  disableUser: (id: string) => client.put(`/admin/users/${id}/disable`),
  enableUser: (id: string) => client.put(`/admin/users/${id}/enable`),
  killSwitch: () => client.post('/admin/kill-switch'),
  resumeTrading: () => client.post('/admin/resume-trading'),
  getSystemHealth: () => client.get<SystemHealth>('/admin/system-health').then((r) => r.data),
  forceStopBot: (id: string) => client.post(`/admin/bots/${id}/force-stop`),
};

// --- Balances ---
export const balances = {
  list: () => client.get<Balance[]>('/balances').then((r) => r.data),
};

export default client;
