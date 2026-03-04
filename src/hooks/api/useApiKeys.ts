import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiKeys as apiKeysApi } from '@/lib/api';
import type { AddApiKeyRequest } from '@/types';

export function useApiKeys() {
  return useQuery({
    queryKey: ['api-keys'],
    queryFn: apiKeysApi.list,
  });
}

export function useAddApiKey() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: AddApiKeyRequest) => apiKeysApi.add(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['api-keys'] }),
  });
}

export function useDeleteApiKey() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => apiKeysApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['api-keys'] }),
  });
}

export function useTestApiKey() {
  return useMutation({
    mutationFn: (id: string) => apiKeysApi.test(id),
  });
}
