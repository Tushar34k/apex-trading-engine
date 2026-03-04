# TradeEngine — Backend Architecture Documentation

## Table of Contents
1. [System Architecture](#system-architecture)
2. [Database Schema](#database-schema)
3. [API Contracts](#api-contracts)
4. [Strategy Framework Design](#strategy-framework)
5. [Risk Engine Logic](#risk-engine)
6. [Execution Engine](#execution-engine)
7. [Backtesting Engine](#backtesting-engine)
8. [WebSocket Configuration](#websocket)
9. [Docker Configuration](#docker)

---

## 1. System Architecture <a name="system-architecture"></a>

### Modular Monolith Structure

```
com.tradeengine/
├── auth/           # Authentication & Authorization (JWT + Roles)
├── user/           # User & API Key Management
├── exchange/       # Exchange Integration Layer
├── market/         # Market Data Engine
├── strategy/       # Strategy Framework Engine
├── risk/           # Risk Management Engine
├── portfolio/      # Portfolio Risk Engine
├── execution/      # Execution Engine
├── backtest/       # Backtesting Engine
├── paper/          # Paper Trading Engine
├── analytics/      # Analytics & Performance Engine
├── admin/          # Admin & Monitoring Module
├── common/         # Shared utilities, DTOs, exceptions
└── config/         # Spring configuration classes
```

### Each Module Contains

```
module/
├── controller/     # REST endpoints
├── service/        # Business logic
├── repository/     # Data access (JPA)
├── model/          # JPA entities
├── dto/            # Request/Response DTOs
├── mapper/         # Entity ↔ DTO mappers
└── event/          # Domain events (for future extraction)
```

### Key Spring Dependencies

```xml
<dependencies>
    <!-- Core -->
    <dependency>spring-boot-starter-web</dependency>
    <dependency>spring-boot-starter-data-jpa</dependency>
    <dependency>spring-boot-starter-security</dependency>
    <dependency>spring-boot-starter-websocket</dependency>
    <dependency>spring-boot-starter-data-redis</dependency>
    <dependency>spring-boot-starter-validation</dependency>
    
    <!-- JWT -->
    <dependency>io.jsonwebtoken:jjwt-api:0.12.3</dependency>
    
    <!-- Database -->
    <dependency>org.postgresql:postgresql</dependency>
    <dependency>org.flywaydb:flyway-core</dependency>
    
    <!-- Encryption -->
    <dependency>org.jasypt:jasypt-spring-boot-starter</dependency>
    
    <!-- Scheduling -->
    <dependency>spring-boot-starter-quartz</dependency>
    
    <!-- Exchange SDK -->
    <dependency>org.knowm.xchange:xchange-binance</dependency>
    
    <!-- Monitoring -->
    <dependency>spring-boot-starter-actuator</dependency>
    <dependency>io.micrometer:micrometer-registry-prometheus</dependency>
</dependencies>
```

---

## 2. Database Schema <a name="database-schema"></a>

### Users & Auth

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    is_active BOOLEAN DEFAULT TRUE
);

CREATE TYPE app_role AS ENUM ('ADMIN', 'USER');

CREATE TABLE user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE NOT NULL,
    role app_role NOT NULL,
    UNIQUE (user_id, role)
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    exchange VARCHAR(50) NOT NULL,
    label VARCHAR(100),
    api_key_encrypted TEXT NOT NULL,        -- AES-256 encrypted
    api_secret_encrypted TEXT NOT NULL,     -- AES-256 encrypted
    permissions VARCHAR(50) NOT NULL DEFAULT 'TRADE_ONLY', -- TRADE_ONLY | READ_ONLY
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    last_used_at TIMESTAMP
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    action VARCHAR(50) NOT NULL,           -- LOGIN, ORDER_PLACED, BOT_ACTIVATED, BOT_DEACTIVATED
    details JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Strategy Framework

```sql
CREATE TABLE strategies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    version VARCHAR(20) NOT NULL,
    type VARCHAR(50) NOT NULL,             -- TREND_FOLLOWING, BREAKOUT, PULLBACK
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE strategy_parameters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID REFERENCES strategies(id) ON DELETE CASCADE,
    param_key VARCHAR(50) NOT NULL,
    param_value VARCHAR(255) NOT NULL,
    param_type VARCHAR(20) NOT NULL,       -- INTEGER, DOUBLE, STRING, BOOLEAN
    description TEXT,
    UNIQUE (strategy_id, param_key)
);

CREATE TABLE strategy_performance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID REFERENCES strategies(id),
    total_return DECIMAL(10,4),
    win_rate DECIMAL(5,4),
    sharpe_ratio DECIMAL(6,4),
    max_drawdown DECIMAL(10,4),
    profit_factor DECIMAL(6,4),
    total_trades INTEGER,
    calculated_at TIMESTAMP DEFAULT NOW()
);
```

### Trading Bots

```sql
CREATE TABLE trading_bots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    strategy_id UUID REFERENCES strategies(id),
    api_key_id UUID REFERENCES api_keys(id),
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(10) NOT NULL,        -- 1m, 5m, 15m, 1H, 4H, 1D
    mode VARCHAR(20) NOT NULL DEFAULT 'PAPER', -- LIVE, PAPER
    status VARCHAR(20) NOT NULL DEFAULT 'STOPPED', -- RUNNING, PAUSED, STOPPED
    created_at TIMESTAMP DEFAULT NOW(),
    started_at TIMESTAMP,
    stopped_at TIMESTAMP
);
```

### Orders & Trades

```sql
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id UUID REFERENCES trading_bots(id),
    user_id UUID REFERENCES users(id),
    exchange_order_id VARCHAR(100),
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,             -- BUY, SELL
    type VARCHAR(20) NOT NULL,             -- MARKET, LIMIT
    quantity DECIMAL(20,8) NOT NULL,
    price DECIMAL(20,8),
    filled_quantity DECIMAL(20,8) DEFAULT 0,
    avg_fill_price DECIMAL(20,8),
    status VARCHAR(20) NOT NULL,           -- PENDING, FILLED, PARTIALLY_FILLED, CANCELLED, FAILED
    slippage DECIMAL(10,6),
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    filled_at TIMESTAMP
);

CREATE TABLE trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id UUID REFERENCES trading_bots(id),
    user_id UUID REFERENCES users(id),
    entry_order_id UUID REFERENCES orders(id),
    exit_order_id UUID REFERENCES orders(id),
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    entry_price DECIMAL(20,8) NOT NULL,
    exit_price DECIMAL(20,8),
    quantity DECIMAL(20,8) NOT NULL,
    pnl DECIMAL(20,8),
    pnl_percent DECIMAL(10,6),
    stop_loss DECIMAL(20,8),
    take_profit DECIMAL(20,8),
    status VARCHAR(20) NOT NULL,           -- OPEN, CLOSED
    opened_at TIMESTAMP DEFAULT NOW(),
    closed_at TIMESTAMP
);

CREATE TABLE trade_explanations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id UUID REFERENCES trades(id) ON DELETE CASCADE,
    strategy_name VARCHAR(100),
    strategy_version VARCHAR(20),
    market_regime VARCHAR(30),             -- TRENDING, RANGING, HIGH_VOLATILITY
    indicator_snapshot JSONB,              -- { "ema12": 67000, "ema26": 66500, "atr14": 1200, "adx": 35 }
    risk_settings JSONB,                   -- { "riskPercent": 1.5, "stopLossDistance": 1650 }
    entry_reason TEXT,
    exit_reason TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Risk Configuration

```sql
CREATE TABLE risk_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    config_key VARCHAR(50) NOT NULL,
    config_value VARCHAR(255) NOT NULL,
    level VARCHAR(20) NOT NULL,            -- TRADE, DAILY, PORTFOLIO
    UNIQUE (user_id, config_key)
);

-- Default risk configs:
-- TRADE level: risk_per_trade_pct=1.5, min_risk_reward=1.5, require_stop_loss=true
-- DAILY level: max_daily_loss_pct=5.0, auto_stop_on_limit=true
-- PORTFOLIO level: max_total_exposure_pct=60, max_symbol_exposure_pct=25, max_correlation=0.7, drawdown_reduction_threshold=10
```

### Backtest Results

```sql
CREATE TABLE backtest_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    strategy_id UUID REFERENCES strategies(id),
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(10) NOT NULL,
    start_date TIMESTAMP NOT NULL,
    end_date TIMESTAMP NOT NULL,
    initial_balance DECIMAL(20,8) NOT NULL,
    final_balance DECIMAL(20,8),
    total_return DECIMAL(10,4),
    win_rate DECIMAL(5,4),
    max_drawdown DECIMAL(10,4),
    sharpe_ratio DECIMAL(6,4),
    profit_factor DECIMAL(6,4),
    total_trades INTEGER,
    status VARCHAR(20) DEFAULT 'RUNNING',  -- RUNNING, COMPLETED, FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE backtest_trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    backtest_run_id UUID REFERENCES backtest_runs(id) ON DELETE CASCADE,
    entry_time TIMESTAMP,
    exit_time TIMESTAMP,
    side VARCHAR(10),
    entry_price DECIMAL(20,8),
    exit_price DECIMAL(20,8),
    quantity DECIMAL(20,8),
    pnl DECIMAL(20,8),
    pnl_percent DECIMAL(10,6)
);
```

---

## 3. API Contracts <a name="api-contracts"></a>

### Authentication

```
POST   /api/auth/register       Register new user
POST   /api/auth/login          Login, returns JWT
POST   /api/auth/refresh        Refresh JWT token
```

### Users

```
GET    /api/users/me            Get current user profile
PUT    /api/users/me            Update profile
```

### API Keys

```
GET    /api/api-keys            List user's API keys
POST   /api/api-keys            Add new API key
DELETE /api/api-keys/{id}       Remove API key
```

### Strategies

```
GET    /api/strategies                  List all strategies
GET    /api/strategies/{id}             Get strategy details
POST   /api/strategies                  Create strategy (ADMIN)
PUT    /api/strategies/{id}             Update strategy
GET    /api/strategies/{id}/parameters  Get parameters
PUT    /api/strategies/{id}/parameters  Update parameters
GET    /api/strategies/{id}/performance Get performance metrics
```

### Trading Bots

```
GET    /api/bots                List user's bots
POST   /api/bots                Create bot
POST   /api/bots/{id}/start     Start bot
POST   /api/bots/{id}/stop      Stop bot
POST   /api/bots/{id}/pause     Pause bot
DELETE /api/bots/{id}           Delete bot
```

### Orders & Trades

```
GET    /api/orders              List orders (with filters)
GET    /api/trades              List trades (with filters)
GET    /api/trades/{id}         Get trade details + explanation
GET    /api/positions           List open positions
```

### Risk

```
GET    /api/risk/config         Get risk config
PUT    /api/risk/config         Update risk config
GET    /api/risk/status         Get current risk status
GET    /api/risk/exposure       Get exposure breakdown
```

### Backtesting

```
POST   /api/backtest/run        Start backtest
GET    /api/backtest/results    List backtest results
GET    /api/backtest/{id}       Get backtest details
GET    /api/backtest/{id}/trades Get backtest trades
```

### Analytics

```
GET    /api/analytics/equity-curve        Equity curve data
GET    /api/analytics/monthly-returns     Monthly return breakdown
GET    /api/analytics/strategy-comparison Strategy comparison
GET    /api/analytics/performance         Overall performance metrics
```

### Admin

```
GET    /api/admin/users         List all users
PUT    /api/admin/users/{id}/disable  Disable user
POST   /api/admin/kill-switch   Emergency stop all trading
GET    /api/admin/system-health System health status
GET    /api/admin/exposure      System-wide exposure
POST   /api/admin/bots/{id}/force-stop  Force stop a bot
```

### WebSocket Endpoints

```
WS     /ws/market-data          Live OHLC + ticker data
WS     /ws/positions            Real-time position updates
WS     /ws/orders               Order status updates
WS     /ws/notifications        System alerts
```

---

## 4. Strategy Framework Design <a name="strategy-framework"></a>

### Base Strategy Interface

```java
public interface TradingStrategy {
    String getName();
    String getVersion();
    
    SignalResult evaluate(MarketData data, MarketRegime regime, StrategyParameters params);
    
    boolean isApplicable(MarketRegime regime);
}
```

### Market Regime Detection

```java
public class MarketRegimeDetector {
    
    public MarketRegime detect(List<Candle> candles) {
        double adx = calculateADX(candles, 14);
        boolean emaAligned = isEmaAligned(candles);
        double atrExpansion = calculateATRExpansion(candles);
        
        if (atrExpansion > 1.5) return MarketRegime.HIGH_VOLATILITY;
        if (adx > 25 && emaAligned) return MarketRegime.TRENDING;
        return MarketRegime.RANGING;
    }
}
```

### Signal Result

```java
public class SignalResult {
    private SignalType signal;           // BUY, SELL, HOLD
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private double confidence;
    private String reason;
    private Map<String, Double> indicatorSnapshot;
    private MarketRegime regime;
}
```

### Strategy Registration

```java
@Configuration
public class StrategyConfig {
    @Bean
    public StrategyRegistry strategyRegistry(
        TrendFollowingStrategy trendFollowing,
        BreakoutStrategy breakout,
        PullbackStrategy pullback
    ) {
        StrategyRegistry registry = new StrategyRegistry();
        registry.register(trendFollowing);
        registry.register(breakout);
        registry.register(pullback);
        return registry;
    }
}
```

---

## 5. Risk Engine Logic <a name="risk-engine"></a>

### Risk Validation Flow

```java
public class RiskEngine {
    
    public RiskValidationResult validate(TradeRequest request, UserRiskConfig config) {
        List<RiskViolation> violations = new ArrayList<>();
        
        // 1. Trade-level checks
        validateTradeRisk(request, config, violations);
        
        // 2. Daily risk checks
        validateDailyRisk(request, config, violations);
        
        // 3. Portfolio-level checks
        validatePortfolioRisk(request, config, violations);
        
        return new RiskValidationResult(violations.isEmpty(), violations);
    }
    
    private void validateTradeRisk(TradeRequest req, UserRiskConfig cfg, List<RiskViolation> v) {
        // Stop-loss required
        if (req.getStopLoss() == null) {
            v.add(new RiskViolation("STOP_LOSS_REQUIRED", "Stop-loss is mandatory"));
        }
        
        // Risk per trade
        double riskPct = calculateRiskPercent(req);
        if (riskPct > cfg.getMaxRiskPerTrade()) {
            v.add(new RiskViolation("RISK_TOO_HIGH", "Risk " + riskPct + "% exceeds limit"));
        }
        
        // Risk/Reward ratio
        double rrRatio = calculateRiskReward(req);
        if (rrRatio < cfg.getMinRiskReward()) {
            v.add(new RiskViolation("RR_TOO_LOW", "R:R " + rrRatio + " below minimum"));
        }
    }
}
```

### Position Sizing

```java
public double calculatePositionSize(double balance, double riskPercent, double stopLossDistance) {
    return (balance * riskPercent / 100.0) / stopLossDistance;
}
```

---

## 6. Execution Engine <a name="execution-engine"></a>

```java
public class ExecutionEngine {
    
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public OrderResult executeOrder(OrderRequest request) {
        // 1. Validate via risk engine
        RiskValidationResult risk = riskEngine.validate(request);
        if (!risk.isApproved()) throw new RiskRejectedException(risk.getViolations());
        
        // 2. Place order on exchange
        ExchangeOrder exchangeOrder = exchangeClient.placeOrder(request);
        
        // 3. Track slippage
        double slippage = calculateSlippage(request.getExpectedPrice(), exchangeOrder.getFilledPrice());
        
        // 4. Place SL/TP orders
        if (request.getStopLoss() != null) {
            exchangeClient.placeStopLoss(exchangeOrder.getSymbol(), request.getStopLoss());
        }
        if (request.getTakeProfit() != null) {
            exchangeClient.placeTakeProfit(exchangeOrder.getSymbol(), request.getTakeProfit());
        }
        
        // 5. Persist & audit
        orderRepository.save(toEntity(exchangeOrder, slippage));
        auditService.log(AuditAction.ORDER_PLACED, request);
        
        return new OrderResult(exchangeOrder, slippage);
    }
}
```

---

## 7. Backtesting Engine <a name="backtesting-engine"></a>

```java
public class BacktestEngine {
    
    public BacktestResult run(BacktestConfig config) {
        List<Candle> historicalData = marketDataService.getHistorical(
            config.getSymbol(), config.getTimeframe(), config.getStartDate(), config.getEndDate()
        );
        
        TradingStrategy strategy = strategyRegistry.get(config.getStrategyId());
        StrategyParameters params = paramService.getParameters(config.getStrategyId());
        VirtualAccount account = new VirtualAccount(config.getInitialBalance());
        
        for (int i = config.getWarmupPeriod(); i < historicalData.size(); i++) {
            List<Candle> window = historicalData.subList(0, i + 1);
            MarketRegime regime = regimeDetector.detect(window);
            MarketData marketData = MarketData.from(window);
            
            SignalResult signal = strategy.evaluate(marketData, regime, params);
            
            if (signal.getSignal() != SignalType.HOLD) {
                // Apply risk engine
                double positionSize = riskEngine.calculatePositionSize(
                    account.getBalance(), params.getRiskPercent(), signal.getStopLossDistance()
                );
                account.executeTrade(signal, positionSize, historicalData.get(i));
            }
            
            // Check stop-loss / take-profit on open positions
            account.updatePositions(historicalData.get(i));
        }
        
        return account.generateReport();
    }
}
```

---

## 8. WebSocket Configuration <a name="websocket"></a>

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins("*")
                .withSockJS();
    }
}

// Market Data Publisher
@Service
public class MarketDataPublisher {
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    public void publishPrice(String symbol, PriceUpdate update) {
        messagingTemplate.convertAndSend("/topic/market/" + symbol, update);
    }
    
    public void publishPositionUpdate(String userId, PositionUpdate update) {
        messagingTemplate.convertAndSendToUser(userId, "/topic/positions", update);
    }
}
```

---

## 9. Docker Configuration <a name="docker"></a>

### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### docker-compose.yml

```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/tradeengine
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_REDIS_HOST: redis
      JWT_SECRET: ${JWT_SECRET}
      AES_ENCRYPTION_KEY: ${AES_KEY}
    depends_on:
      - db
      - redis

  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: tradeengine
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  pgdata:
```

### application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tradeengine
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
  redis:
    host: localhost
    port: 6379

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000  # 24 hours

encryption:
  aes-key: ${AES_ENCRYPTION_KEY}

exchange:
  binance:
    base-url: https://api.binance.com
    ws-url: wss://stream.binance.com:9443/ws

risk:
  defaults:
    risk-per-trade: 1.5
    max-daily-loss: 5.0
    max-total-exposure: 60.0
    max-symbol-exposure: 25.0
    min-risk-reward: 1.5
```

---

## Frontend Connection Points

The React frontend connects to this backend via:
1. **REST API**: All CRUD operations through Axios/fetch with JWT bearer token
2. **WebSocket**: STOMP over SockJS for live market data, position updates, order status
3. **Authentication**: JWT stored in httpOnly cookie or memory (not localStorage)

### Example API Client Setup

```typescript
// src/lib/api.ts
const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

const api = {
  get: (path: string) => fetch(`${API_BASE}${path}`, { headers: authHeaders() }),
  post: (path: string, body: any) => fetch(`${API_BASE}${path}`, { method: 'POST', headers: authHeaders(), body: JSON.stringify(body) }),
};
```
