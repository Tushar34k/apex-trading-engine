import { useQuery } from '@tanstack/react-query';
import { positions as positionsApi, orders as ordersApi, trades as tradesApi } from '@/lib/api';

export function usePositions() {
  return useQuery({
    queryKey: ['positions'],
    queryFn: positionsApi.list,
    refetchInterval: 5000,
  });
}

export function useOrders(params?: { botId?: string }) {
  return useQuery({
    queryKey: ['orders', params],
    queryFn: () => ordersApi.list(params),
    refetchInterval: 5000,
  });
}

export function useTrades(params?: { botId?: string }) {
  return useQuery({
    queryKey: ['trades', params],
    queryFn: () => tradesApi.list(params),
    refetchInterval: 5000,
  });
}
