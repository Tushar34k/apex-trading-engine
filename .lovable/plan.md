

# Production Upgrade Plan: Mock to Real Trading System

## Current State

Every page and component uses hardcoded inline arrays for data (positions, bots, strategies, trades, candles, risk rules, users, API keys, analytics). The TradingChart generates random candles. There is no API client, no authentication, no WebSocket client, and no TypeScript types matching the backend schema.

## Architecture Overview

```text
React Frontend
├── src/lib/api.ts              ← Axios/fetch client with JWT auth headers
├── src/lib/ws.ts               ← WebSocket (STOMP/SockJS) client
├── src/types/                  ← TypeScript DTOs matching backend schema
├── src/contexts/AuthContext.tsx ← JWT auth state, login/logout/refresh
├── src/hooks/api/              ← React Query hooks per domain
├── src/pages/Login.tsx         ← Auth pages
└── All existing pages          ← Refactored to use hooks instead of mock data
```

## Implementation Steps

### 1. TypeScript Types (`src/types/`)

Create type definitions matching the backend schema: `User`, `ApiKey`, `Strategy`, `StrategyParameter`, `TradingBot`, `Order`, `Trade`, `TradeExplanation`, `RiskConfig`, `BacktestRun`, `BacktestTrade`, `Position`, `MarketData`, `CandleData`, plus WebSocket event types (`TradeOpenedEvent`, `TradeClosedEvent`, `StopLossTriggeredEvent`, `TakeProfitTriggeredEvent`, `PositionUpdate`, `PriceUpdate`).

### 2. API Client (`src/lib/api.ts`)

- Configurable base URL via `VITE_API_URL` env var
- JWT bearer token injection from auth context
- Automatic token refresh on 401
- Request/response interceptors for error handling
- Typed methods for all 30+ REST endpoints from the backend architecture doc

### 3. WebSocket Client (`src/lib/ws.ts`)

- STOMP over SockJS connection to `/ws`
- Auto-reconnect with exponential backoff
- Subscribe helpers for `/topic/market/{symbol}`, `/user/topic/positions`, `/user/topic/orders`, `/topic/notifications`
- React hook `useWebSocket` exposing connection state and subscribe/unsubscribe

### 4. Auth Context (`src/contexts/AuthContext.tsx`)

- Login/register/logout/refresh token flows
- Store JWT in memory (not localStorage per security requirements)
- `AuthProvider` wrapping the app
- `useAuth` hook exposing user, role, loading state
- Protected route wrapper redirecting unauthenticated users to `/login`

### 5. Login and Register Pages

- `/login` and `/register` routes outside AppLayout
- Form validation with zod + react-hook-form
- Role display in TopBar user avatar area

### 6. React Query Hooks (`src/hooks/api/`)

One hook file per domain, replacing all inline mock data:
- `useBots`, `useStartBot`, `useStopBot`, `useCreateBot`
- `useStrategies`, `useStrategyParams`, `useUpdateStrategyParams`
- `usePositions`, `useOrders`, `useTrades`, `useTradeDetail`
- `useRiskConfig`, `useUpdateRiskConfig`, `useRiskStatus`, `useExposure`
- `useBacktestRun`, `useBacktestResults`, `useBacktestTrades`
- `useApiKeys`, `useAddApiKey`, `useDeleteApiKey`
- `useAnalytics`, `useEquityCurve`, `useMonthlyReturns`
- `useAdminUsers`, `useKillSwitch`, `useSystemHealth`
- `useBalances`

### 7. Component Refactors (all mock data removed)

| Component | Change |
|---|---|
| `TradingChart` | Fetch candles from API (`getCandles`), subscribe to WebSocket for live updates, render real trade markers from `useTrades` |
| `PositionsTable` | Use `usePositions` hook, live updates via WS |
| `ActiveBots` | Use `useBots` hook, start/stop actions call API |
| `EquityCurve` | Use `useEquityCurve` hook |
| `TopBar` | Show real connection status, real BTC/ETH prices from WS, real active bot count, user initials from auth |
| `Index` (Dashboard) | All StatCards driven by `useAnalytics` + `useRiskStatus` |
| `Strategies` | `useStrategies` hook, settings button opens param editor dialog calling `useUpdateStrategyParams` |
| `RiskControl` | `useRiskConfig` + `useRiskStatus` + `useExposure`, editable config fields |
| `Portfolio` | `useBalances` + `usePositions` + `useMonthlyReturns` |
| `Backtesting` | `useBacktestResults`, "Run Backtest" button calls `useBacktestRun` mutation with form inputs |
| `PaperTrading` | `useTrades` filtered by mode=PAPER |
| `Analytics` | `useAnalytics` + `useMonthlyReturns` + strategy comparison from API |
| `ApiKeys` | `useApiKeys`, add/delete mutations, test endpoint call |
| `Admin` | `useAdminUsers`, `useSystemHealth`, `useKillSwitch` mutation |

### 8. Trade Explanation Panel

- New `TradeDetailDialog` component
- Clicking a trade row opens dialog showing: strategy name/version, market regime, indicator snapshot (JSON rendered as key-value), risk settings, entry/exit reasons
- Data from `useTradeDetail(tradeId)` which calls `GET /api/trades/{id}`

### 9. Updated Backend Architecture Docs

- Add WebSocket event payload schemas
- Add reconciliation job spec
- Add trailing stop and partial close to execution engine
- Add refresh token endpoint details
- Add rate limiting config
- Add Viewer role to auth

### 10. Environment Configuration

- Add `.env.example` with `VITE_API_URL` and `VITE_WS_URL`
- Document connection setup in README

## File Count

~15 new files, ~12 modified files. No mock data remains anywhere in the codebase.

## Constraints

- The frontend cannot execute backend logic (order placement, risk validation, strategy evaluation). All business logic runs on the Spring Boot backend.
- This plan builds the complete frontend integration layer. The backend must be running and accessible for the app to function.

