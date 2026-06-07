# Risk Management & Kill Switch Overhaul

This is a large change set (27 items) touching trading-critical code. I'll group them into 5 phases so each can be reviewed and tested independently before moving on. Phases 1–2 are highest priority (real-money safety); 3–5 add edge and UX.

## Phase 1 — Position sizing & exposure (items 1–4)
- `RiskManagementService`: dynamic lot sizing from **live** balance fetched per order (no cache); risk 1–2% per trade (configurable, default 1%).
- `PositionRiskValidator`: cap aggregate open notional at 10% of equity; reject + log when breached.
- Hard internal leverage cap = 3x in `PositionRiskValidator.validateLeverage` (ignore exchange max).
- New `BalanceService.fetchLive(botId)` bypassing cache, called from `TradeExecutionQueue` immediately pre-order.

## Phase 2 — Kill switch redesign (items 5–11, 12–13)
- Introduce `HaltMode { SOFT, HARD }` in `KillSwitchService`:
  - SOFT = block new entries, allow position mgmt (SL/TP/trailing).
  - HARD = full freeze (current behavior).
- 60-second post-reset cooldown gate in `executionQueue.submit()`.
- Auto-trigger only after **3 consecutive losses** (replace current single-failure path); 5-second debounce on auto-triggers.
- Activation reason must include numeric context: `"3 losses in 47m, -4.3% DD"`.
- Daily-loss 5% → HARD halt until UTC midnight (scheduled reset job).
- Weekly DD 10% → HARD halt, bot marked `REQUIRES_REVIEW`.
- Volatility-triggered SOFT halts auto-reset after 15 min (configurable toggle).
- API: `POST /api/kill-switch/resume-soft` one-click resume for SOFT halts.

## Phase 3 — Position management (items 14, 15, 21)
- On entry, `PositionTracker` auto-attaches trailing stop = 1.5× ATR.
- Monitor loop: when unrealized PnL ≥ +1%, move SL to entry (break-even).
- Partial TP: close 50% at 1R, leave 50% with trailing stop.

## Phase 4 — Entry quality (items 16–20, 22, 23)
In `StrategyRunner` / `TradeQualityScorer`:
- Hard reject RR < 2:1 (pre-entry calc using SL & TP).
- Volume > 20-SMA filter.
- Per-bot `trendFilterEnabled` setting: long only above 200 EMA, short only below.
- 3-candle same-pair cooldown.
- Session filter 13:00–17:00 UTC (per-bot toggle).
- ATR-spike skip: current ATR > 3× ATR14-avg.
- Step-down sizing: 0.5× after 2 consecutive losses; reset on win.

## Phase 5 — Dashboard & telemetry (items 24–27, plus 9)
- Backend endpoint `/api/risk-monitor/dashboard` returning: daily P&L%, weekly DD%, remaining daily loss budget, current open exposure %, 7d/30d win rate, avg R:R, profit factor.
- Extend `RejectionMetricsService` to capture every rejected order with reason → exposed via existing debug API + new UI panel.
- New components: `RiskBudgetBar`, `PerformanceStats`, `RejectedOrdersFeed` on dashboard.
- `KillSwitchModal`: show structured trigger reason; add "Resume — I've reviewed" button for SOFT halts.

## Technical notes
- Add migration `V5__add_halt_mode_and_review_flag.sql`: `bots.requires_review boolean`, `kill_switch_state` table (mode, reason, triggered_at, auto_reset_at).
- All new thresholds live in DB-driven `risk_settings` (already pattern in codebase) — no hardcoded values, per memory rule.
- Telemetry-first: every gate emits `RejectionMetricsService.record(...)` before returning.
- Tests: extend `RiskCircuitBreakerTest`, `PositionSizingCircuitBreakerTest`, add `KillSwitchHaltModeTest`, `DrawdownProtectionTest`.

## Confirm before I start
1. Default per-trade risk: **1%** or **2%**? (I'll use 1%, configurable.)
2. Session filter (item 20) and trend filter (item 18) — apply globally or per-bot toggle defaulting OFF? (I'll do per-bot, default OFF, so existing bots aren't suddenly silenced.)
3. OK to proceed phase-by-phase, shipping Phase 1 first and pausing for your review before Phase 2?
