import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { wsClient } from '@/lib/ws';
import type { WebSocketEvent } from '@/types';

/**
 * Listens to WebSocket trade/order/position events and 
 * invalidates the corresponding React Query caches for live updates.
 */
export function useTradeEvents() {
  const queryClient = useQueryClient();

  useEffect(() => {
    const unsubs: (() => void)[] = [];

    // Listen for trade events (opened/closed)
    unsubs.push(
      wsClient.subscribeTrades((event: WebSocketEvent) => {
        if (event.type === 'TRADE_OPENED' || event.type === 'TRADE_CLOSED') {
          queryClient.invalidateQueries({ queryKey: ['positions'] });
          queryClient.invalidateQueries({ queryKey: ['trades'] });
          queryClient.invalidateQueries({ queryKey: ['analytics-performance'] });
          queryClient.invalidateQueries({ queryKey: ['equity-curve'] });
          queryClient.invalidateQueries({ queryKey: ['balances'] });
        }
      })
    );

    // Listen for order updates
    unsubs.push(
      wsClient.subscribeOrders((event: WebSocketEvent) => {
        if (event.type === 'ORDER_UPDATE') {
          queryClient.invalidateQueries({ queryKey: ['orders'] });
        }
      })
    );

    // Listen for position updates
    unsubs.push(
      wsClient.subscribePositions((event: WebSocketEvent) => {
        if (event.type === 'POSITION_UPDATE') {
          queryClient.invalidateQueries({ queryKey: ['positions'] });
        }
      })
    );

    return () => unsubs.forEach(u => u());
  }, [queryClient]);
}
