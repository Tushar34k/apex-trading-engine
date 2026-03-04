import { useQuery } from '@tanstack/react-query';
import { market as marketApi } from '@/lib/api';

export function useCandles(symbol: string, timeframe: string, limit?: number) {
  return useQuery({
    queryKey: ['candles', symbol, timeframe, limit],
    queryFn: () => marketApi.getCandles(symbol, timeframe, limit),
    refetchInterval: 60000,
  });
}
