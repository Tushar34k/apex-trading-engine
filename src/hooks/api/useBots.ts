import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { bots as botsApi } from '@/lib/api';
import client from '@/lib/api';
import type { CreateBotRequest } from '@/types';
import type { CreateBotRequest } from '@/types';

export function useBots() {
  return useQuery({
    queryKey: ['bots'],
    queryFn: botsApi.list,
    refetchInterval: 5000,
  });
}

export function useStartBot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => botsApi.start(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bots'] }),
  });
}

export function useStopBot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => botsApi.stop(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bots'] }),
  });
}

export function useCreateBot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateBotRequest) => botsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bots'] }),
  });
}

export function useDeleteBot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => botsApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bots'] }),
  });
}

export function useUpdateBotParams() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ botId, params }: { botId: string; params: string }) =>
      client.patch(`/bots/${botId}/params`, { strategyParams: params }).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bots'] }),
  });
}
