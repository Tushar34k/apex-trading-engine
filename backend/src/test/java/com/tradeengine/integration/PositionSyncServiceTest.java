package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import com.tradeengine.model.TradingBot;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.service.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests PositionSyncService reconciliation: phantom positions, untracked positions,
 * size mismatches, and startup recovery.
 */
class PositionSyncServiceTest {

    private PositionTracker positionTracker;
    private BotRepository botRepo;
    private ApiKeyRepository apiKeyRepo;
    private ApiKeyService apiKeyService;
    private ExchangeFactory exchangeFactory;
    private ExchangeClient mockClient;
    private SymbolMapperService symbolMapper;
    private NotificationService notificationService;
    private PositionSyncService syncService;

    private UUID botId = UUID.randomUUID();
    private UUID userId = UUID.randomUUID();
    private UUID apiKeyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        positionTracker = new PositionTracker();
        botRepo = mock(BotRepository.class);
        apiKeyRepo = mock(ApiKeyRepository.class);
        apiKeyService = mock(ApiKeyService.class);
        exchangeFactory = mock(ExchangeFactory.class);
        mockClient = mock(ExchangeClient.class);
        symbolMapper = mock(SymbolMapperService.class);
        notificationService = mock(NotificationService.class);

        syncService = new PositionSyncService(
            positionTracker, botRepo, apiKeyRepo, apiKeyService,
            exchangeFactory, symbolMapper, notificationService
        );

        // Default mocks
        when(exchangeFactory.getClient(anyString())).thenReturn(mockClient);
        when(mockClient.resolveBaseUrl(anyString())).thenReturn("http://localhost");
        when(apiKeyService.decryptApiKey(any())).thenReturn("key");
        when(apiKeyService.decryptApiSecret(any())).thenReturn("secret");
        when(symbolMapper.resolveSymbol(anyString(), anyString())).thenReturn("BTCUSDT");
    }

    private TradingBot createBot() {
        TradingBot bot = new TradingBot();
        bot.setId(botId);
        bot.setUserId(userId);
        bot.setApiKeyId(apiKeyId);
        bot.setExchangeMode("TESTNET");
        bot.setSymbol("BTC/USDT");
        bot.setName("TestBot");
        bot.setStatus("RUNNING");
        return bot;
    }

    private UserApiKey createApiKey() {
        UserApiKey key = new UserApiKey();
        key.setId(apiKeyId);
        key.setUserId(userId);
        key.setExchange("BINANCE");
        key.setLabel("test");
        key.setApiKeyEncrypted("enc");
        key.setApiSecretEncrypted("enc");
        return key;
    }

    @Test
    @DisplayName("CASE A: Phantom position — bot has position, exchange has none")
    void caseA_phantomPosition() {
        TradingBot bot = createBot();
        bot.setHasOpenPosition(true);
        bot.setEntryPrice(new BigDecimal("42000"));
        bot.setQuantity(new BigDecimal("0.01"));

        // Register internal position
        positionTracker.registerPosition(PositionTracker.TrackedPosition.builder()
            .botId(botId).userId(userId).symbol("BTCUSDT").exchange("BINANCE")
            .exchangeMode("TESTNET").entryPrice(new BigDecimal("42000"))
            .quantity(new BigDecimal("0.01")).apiKey("key").apiSecret("secret")
            .exchangeBaseUrl("http://localhost").build());

        when(botRepo.findByStatus("RUNNING")).thenReturn(List.of(bot));
        when(apiKeyRepo.findById(apiKeyId)).thenReturn(Optional.of(createApiKey()));
        when(mockClient.getOpenPositions(any(), any(), any())).thenReturn(List.of()); // empty!

        syncService.syncPositions();

        assertFalse(positionTracker.hasPosition(botId));
        verify(botRepo).save(argThat(b -> !b.isHasOpenPosition()));
    }

    @Test
    @DisplayName("CASE B: Untracked position — exchange has position, bot has none")
    void caseB_untrackedPosition() {
        TradingBot bot = createBot();
        bot.setHasOpenPosition(false);

        ExchangePosition exPos = ExchangePosition.builder()
            .exchange("BINANCE").symbol("BTCUSDT").side("LONG")
            .size(new BigDecimal("0.02")).entryPrice(new BigDecimal("41000"))
            .unrealizedPnl(new BigDecimal("50")).build();

        when(botRepo.findByStatus("RUNNING")).thenReturn(List.of(bot));
        when(apiKeyRepo.findById(apiKeyId)).thenReturn(Optional.of(createApiKey()));
        when(mockClient.getOpenPositions(any(), any(), any())).thenReturn(List.of(exPos));

        syncService.syncPositions();

        assertTrue(positionTracker.hasPosition(botId));
        assertEquals(new BigDecimal("0.02"), positionTracker.getPosition(botId).get().getQuantity());
        verify(botRepo).save(argThat(TradingBot::isHasOpenPosition));
    }

    @Test
    @DisplayName("CASE C: Size mismatch — exchange size differs from internal")
    void caseC_sizeMismatch() {
        TradingBot bot = createBot();
        bot.setHasOpenPosition(true);
        bot.setQuantity(new BigDecimal("0.01"));

        positionTracker.registerPosition(PositionTracker.TrackedPosition.builder()
            .botId(botId).userId(userId).symbol("BTCUSDT").exchange("BINANCE")
            .exchangeMode("TESTNET").entryPrice(new BigDecimal("42000"))
            .quantity(new BigDecimal("0.01")).apiKey("key").apiSecret("secret")
            .exchangeBaseUrl("http://localhost").build());

        ExchangePosition exPos = ExchangePosition.builder()
            .exchange("BINANCE").symbol("BTCUSDT").side("LONG")
            .size(new BigDecimal("0.02")).entryPrice(new BigDecimal("42000"))
            .unrealizedPnl(BigDecimal.ZERO).build();

        when(botRepo.findByStatus("RUNNING")).thenReturn(List.of(bot));
        when(apiKeyRepo.findById(apiKeyId)).thenReturn(Optional.of(createApiKey()));
        when(mockClient.getOpenPositions(any(), any(), any())).thenReturn(List.of(exPos));

        syncService.syncPositions();

        assertEquals(new BigDecimal("0.02"), positionTracker.getPosition(botId).get().getQuantity());
        verify(botRepo).save(argThat(b -> b.getQuantity().compareTo(new BigDecimal("0.02")) == 0));
    }

    @Test
    @DisplayName("No running bots — sync is a no-op")
    void noRunningBots() {
        when(botRepo.findByStatus("RUNNING")).thenReturn(List.of());
        syncService.syncPositions();
        verify(apiKeyRepo, never()).findById(any());
    }

    @Test
    @DisplayName("Startup recovery registers exchange positions")
    void startupRecovery() {
        TradingBot bot = createBot();

        ExchangePosition exPos = ExchangePosition.builder()
            .exchange("BINANCE").symbol("BTCUSDT").side("LONG")
            .size(new BigDecimal("0.05")).entryPrice(new BigDecimal("40000"))
            .unrealizedPnl(new BigDecimal("200")).build();

        when(botRepo.findByStatus("RUNNING")).thenReturn(List.of(bot));
        when(apiKeyRepo.findById(apiKeyId)).thenReturn(Optional.of(createApiKey()));
        when(mockClient.getOpenPositions(any(), any(), any())).thenReturn(List.of(exPos));

        syncService.recoverPositionsOnStartup();

        assertTrue(positionTracker.hasPosition(botId));
        assertEquals(1, positionTracker.getOpenPositionCount());
    }
}
