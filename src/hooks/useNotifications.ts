import { useEffect, useState, useCallback } from 'react';
import { wsClient } from '@/lib/ws';
import { toast } from 'sonner';
import type { TradeNotification } from '@/types';

export function useNotifications(maxItems = 50) {
  const [notifications, setNotifications] = useState<TradeNotification[]>([]);

  useEffect(() => {
    const unsub = wsClient.subscribeNotifications((event) => {
      const notif = event as unknown as TradeNotification;
      if (notif.type && notif.message) {
        setNotifications((prev) => [notif, ...prev].slice(0, maxItems));

        // Show diagnostic toast for risk rejections with SL details
        if (notif.type === 'RISK_BLOCKED' && notif.message.includes('SL too tight')) {
          toast.error(`🚫 ${notif.botName}: Trade Rejected`, {
            description: notif.message,
            duration: 10000,
          });
        }
      }
    });
    return unsub;
  }, [maxItems]);

  const clear = useCallback(() => setNotifications([]), []);

  return { notifications, clear };
}
