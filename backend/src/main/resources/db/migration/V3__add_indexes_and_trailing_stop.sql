-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_orders_bot_id ON orders(bot_id);
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_positions_bot_id ON positions(bot_id);
CREATE INDEX IF NOT EXISTS idx_positions_user_id ON positions(user_id);
CREATE INDEX IF NOT EXISTS idx_positions_status ON positions(status);
CREATE INDEX IF NOT EXISTS idx_bots_status ON trading_bots(status);
CREATE INDEX IF NOT EXISTS idx_bots_user_id ON trading_bots(user_id);

-- Add stop_loss and take_profit columns to positions table
ALTER TABLE positions ADD COLUMN IF NOT EXISTS stop_loss DECIMAL(20,8);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS take_profit DECIMAL(20,8);
