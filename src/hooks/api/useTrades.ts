import { useQuery } from '@tanstack/react-query';
import { positions as positionsApi, orders as ordersApi, trades as tradesApi } from '@/lib/api';

export function usePositions() {
  return useQuery({
    queryKey: ['positions'],
    queryFn: positionsApi.list,
    refetchInterval: 5000,
  });
}

export function useOrders(params?: { botId?: string; status?: string; symbol?: string }) {
  return useQuery({
    queryKey: ['orders', params],
    queryFn: () => ordersApi.list(params),
  });
}

export function useTrades(params?: { botId?: string; status?: string; symbol?: string; mode?: string }) {
  return useQuery({
    queryKey: ['trades', params],
    queryFn: () => tradesApi.list(params),
  });
}

export function useTradeDetail(tradeId: string | null) {
  return useQuery({
    queryKey: ['trade-detail', tradeId],
    queryFn: () => tradesApi.get(tradeId!),
    enabled: !!tradeId,
  });
}
