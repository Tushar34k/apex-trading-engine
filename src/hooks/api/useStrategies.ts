import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { strategies as strategiesApi } from '@/lib/api';
import type { StrategyParameter } from '@/types';

export function useStrategies() {
  return useQuery({
    queryKey: ['strategies'],
    queryFn: strategiesApi.list,
  });
}

export function useStrategyParams(strategyId: string | null) {
  return useQuery({
    queryKey: ['strategy-params', strategyId],
    queryFn: () => strategiesApi.getParams(strategyId!),
    enabled: !!strategyId,
  });
}

export function useStrategyPerformance(strategyId: string | null) {
  return useQuery({
    queryKey: ['strategy-performance', strategyId],
    queryFn: () => strategiesApi.getPerformance(strategyId!),
    enabled: !!strategyId,
  });
}

export function useUpdateStrategyParams() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, params }: { id: string; params: Partial<StrategyParameter>[] }) =>
      strategiesApi.updateParams(id, params),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['strategy-params'] }),
  });
}
