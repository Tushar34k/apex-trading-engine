import { useQuery } from '@tanstack/react-query';
import { bots as botsApi } from '@/lib/api';
import type { BotStats } from '@/types';

export function useBotStats(botId: string | null) {
  return useQuery<BotStats>({
    queryKey: ['bot-stats', botId],
    queryFn: () => botsApi.stats(botId!),
    enabled: !!botId,
    refetchInterval: 10000,
  });
}
