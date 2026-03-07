-- Add exchange mode and strategy params to bots
ALTER TABLE trading_bots ADD COLUMN IF NOT EXISTS exchange_mode VARCHAR(20) NOT NULL DEFAULT 'TESTNET';
ALTER TABLE trading_bots ADD COLUMN IF NOT EXISTS strategy_params TEXT;
