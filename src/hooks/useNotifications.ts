import { useEffect, useState, useCallback } from 'react';
import { wsClient } from '@/lib/ws';
import type { TradeNotification } from '@/types';

export function useNotifications(maxItems = 50) {
  const [notifications, setNotifications] = useState<TradeNotification[]>([]);

  useEffect(() => {
    const unsub = wsClient.subscribeNotifications((event) => {
      const notif = event as unknown as TradeNotification;
      if (notif.type && notif.message) {
        setNotifications((prev) => [notif, ...prev].slice(0, maxItems));
      }
    });
    return unsub;
  }, [maxItems]);

  const clear = useCallback(() => setNotifications([]), []);

  return { notifications, clear };
}
