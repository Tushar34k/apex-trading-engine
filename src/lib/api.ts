import axios, { AxiosInstance, AxiosError, InternalAxiosRequestConfig } from 'axios';
import type {
  AuthTokens, LoginRequest, RegisterRequest, User,
  ApiKey, AddApiKeyRequest, ApiKeyTestResult,
  TradingBot, CreateBotRequest,
  Order, Trade, Position, Balance, CandleData,
  AccountBalance, BotStats, BacktestRequest, BacktestResult,
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

client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken && config.headers) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

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

export const auth = {
  login: (data: LoginRequest) =>
    client.post<AuthTokens>('/auth/login', data).then((r) => r.data),
  register: (data: RegisterRequest) =>
    client.post<AuthTokens>('/auth/register', data).then((r) => r.data),
  refresh: (token: string) =>
    client.post<AuthTokens>('/auth/refresh', { refreshToken: token }).then((r) => r.data),
};

export const users = {
  me: () => client.get<User>('/users/me').then((r) => r.data),
};

export const apiKeys = {
  list: () => client.get<ApiKey[]>('/keys').then((r) => r.data),
  add: (data: AddApiKeyRequest) => client.post<ApiKey>('/keys', data).then((r) => r.data),
  delete: (id: string) => client.delete(`/keys/${id}`),
  test: (id: string) => client.post<ApiKeyTestResult>(`/keys/${id}/test`).then((r) => r.data),
};

export const bots = {
  list: () => client.get<TradingBot[]>('/bots').then((r) => r.data),
  create: (data: CreateBotRequest) => client.post<TradingBot>('/bots', data).then((r) => r.data),
  start: (id: string) => client.post(`/bots/${id}/start`).then((r) => r.data),
  stop: (id: string) => client.post(`/bots/${id}/stop`).then((r) => r.data),
  status: (id: string) => client.get(`/bots/${id}/status`).then((r) => r.data),
  delete: (id: string) => client.delete(`/bots/${id}`).then((r) => r.data),
  stats: (id: string) => client.get<BotStats>(`/bots/${id}/stats`).then((r) => r.data),
};

export const orders = {
  list: (params?: { botId?: string }) =>
    client.get<Order[]>('/orders', { params }).then((r) => r.data),
};

export const trades = {
  list: (params?: { botId?: string }) =>
    client.get<Trade[]>('/trades', { params }).then((r) => r.data),
};

export const positions = {
  list: () => client.get<Position[]>('/positions').then((r) => r.data),
};

export const balances = {
  list: () => client.get<Balance[]>('/balances').then((r) => r.data),
};

export const account = {
  balance: (mode?: string) =>
    client.get<AccountBalance>('/account/balance', { params: { mode } }).then((r) => r.data),
  allBalances: (mode?: string) =>
    client.get<AccountBalance[]>('/account/balances', { params: { mode } }).then((r) => r.data),
};

export const market = {
  getCandles: (symbol: string, timeframe: string, limit?: number) =>
    client.get<CandleData[]>('/market/candles', { params: { symbol, timeframe, limit } }).then((r) => r.data),
};

export const backtest = {
  run: (data: BacktestRequest) =>
    client.post<BacktestResult>('/backtest/run', data).then((r) => r.data),
};

export interface ExecutionMetrics {
  queueSize: number;
  totalSubmitted: number;
  totalExecuted: number;
  totalFailed: number;
}

export interface SystemMetrics {
  queueSize: number;
  totalSubmitted: number;
  totalExecuted: number;
  totalFailed: number;
  totalBots: number;
  runningBots: number;
  openPositions: number;
  queue: {
    size: number;
    capacity: number;
    usagePercent: number;
    pendingBots: number;
    totalSubmitted: number;
    totalExecuted: number;
    totalFailed: number;
  };
  killSwitch: {
    active: boolean;
    reason: string | null;
    activatedAt: string | null;
    maxDailyLossPercent: number;
    maxTotalExposureUsdt: number;
  };
  exchangeHealth: {
    circuitBreakerOpen: boolean;
    circuitBreakerOpenedAt: string | null;
    recentErrors: number;
    killSwitchErrors: number;
    maxErrorsPerMinute: number;
  };
}

export const execution = {
  metrics: () =>
    client.get<ExecutionMetrics>('/execution/metrics').then((r) => r.data),
};

export const system = {
  metrics: () =>
    client.get<SystemMetrics>('/system/metrics').then((r) => r.data),
  activateKillSwitch: (reason: string) =>
    client.post('/system/kill-switch/activate', { reason }).then((r) => r.data),
  resetKillSwitch: () =>
    client.post('/system/kill-switch/reset').then((r) => r.data),
};

export default client;
