import { useQuery, useMutation } from '@tanstack/react-query';
import { aiValidation } from '@/lib/api';

export function useAIValidationStats() {
  return useQuery({
    queryKey: ['ai-validation-stats'],
    queryFn: () => aiValidation.stats(),
    refetchInterval: 10000,
  });
}

export function useAIDecisions(limit = 50) {
  return useQuery({
    queryKey: ['ai-decisions', limit],
    queryFn: () => aiValidation.decisions(limit),
    refetchInterval: 10000,
  });
}

export function useAIPreview() {
  return useMutation({
    mutationFn: (data: { symbol: string; side: string; timeframe: string; exchange: string; params?: Record<string, unknown> }) =>
      aiValidation.preview(data),
  });
}
