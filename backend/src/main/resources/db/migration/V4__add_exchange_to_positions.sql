-- Add exchange column to positions for multi-exchange support
ALTER TABLE positions ADD COLUMN IF NOT EXISTS exchange VARCHAR(50);

-- Add index for faster position sync queries
CREATE INDEX IF NOT EXISTS idx_positions_bot_status ON positions(bot_id, status);

-- Add composite index for order lookups
CREATE INDEX IF NOT EXISTS idx_orders_user_created ON orders(user_id, created_at DESC);

-- Add index for API key lookups
CREATE INDEX IF NOT EXISTS idx_api_keys_user_id ON api_keys(user_id);
