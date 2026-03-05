import { useQuery } from '@tanstack/react-query';
import { market as marketApi } from '@/lib/api';

export function useCandles(symbol: string, timeframe: string, limit?: number) {
  return useQuery({
    queryKey: ['candles', symbol, timeframe, limit],
    queryFn: () => marketApi.getCandles(symbol, timeframe, limit),
    refetchInterval: 60000,
  });
}

export function useSupportResistance(symbol: string, timeframe?: string) {
  return useQuery({
    queryKey: ['support-resistance', symbol, timeframe],
    queryFn: () => marketApi.getSupportResistance(symbol, timeframe),
    refetchInterval: 300000, // 5 minutes
  });
}
