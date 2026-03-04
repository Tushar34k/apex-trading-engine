import { useEffect, useState, useCallback } from 'react';
import { wsClient } from '@/lib/ws';
import type { WebSocketEvent, PriceUpdateEvent } from '@/types';

export function useWebSocket() {
  const [connected, setConnected] = useState(wsClient.isConnected());

  useEffect(() => {
    const unsub = wsClient.onConnectionChange(setConnected);
    return () => { unsub(); };
  }, []);

  return { connected };
}

export function useMarketPrice(symbol: string) {
  const [price, setPrice] = useState<number | null>(null);
  const [change24h, setChange24h] = useState<number>(0);

  useEffect(() => {
    return wsClient.subscribeMarket(symbol, (event: WebSocketEvent) => {
      if (event.type === 'PRICE_UPDATE') {
        const e = event as PriceUpdateEvent;
        setPrice(e.price);
        setChange24h(e.change24h);
      }
    });
  }, [symbol]);

  return { price, change24h };
}

export function useWsSubscription(
  destination: string,
  handler: (event: WebSocketEvent) => void
) {
  const stableHandler = useCallback(handler, [handler]);

  useEffect(() => {
    return wsClient.subscribe(destination, stableHandler);
  }, [destination, stableHandler]);
}
