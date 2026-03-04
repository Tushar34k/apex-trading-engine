import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { risk as riskApi } from '@/lib/api';
import type { RiskConfigItem } from '@/types';

export function useRiskConfig() {
  return useQuery({
    queryKey: ['risk-config'],
    queryFn: riskApi.getConfig,
  });
}

export function useUpdateRiskConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (items: Partial<RiskConfigItem>[]) => riskApi.updateConfig(items),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['risk-config'] }),
  });
}

export function useRiskStatus() {
  return useQuery({
    queryKey: ['risk-status'],
    queryFn: riskApi.getStatus,
    refetchInterval: 10000,
  });
}

export function useExposure() {
  return useQuery({
    queryKey: ['risk-exposure'],
    queryFn: riskApi.getExposure,
    refetchInterval: 10000,
  });
}
