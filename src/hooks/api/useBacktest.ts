import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { backtest as backtestApi } from '@/lib/api';
import type { RunBacktestRequest } from '@/types';

export function useBacktestResults() {
  return useQuery({
    queryKey: ['backtest-results'],
    queryFn: backtestApi.listResults,
  });
}

export function useBacktestDetail(id: string | null) {
  return useQuery({
    queryKey: ['backtest-detail', id],
    queryFn: () => backtestApi.get(id!),
    enabled: !!id,
  });
}

export function useBacktestTrades(id: string | null) {
  return useQuery({
    queryKey: ['backtest-trades', id],
    queryFn: () => backtestApi.getTrades(id!),
    enabled: !!id,
  });
}

export function useRunBacktest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: RunBacktestRequest) => backtestApi.run(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['backtest-results'] }),
  });
}
