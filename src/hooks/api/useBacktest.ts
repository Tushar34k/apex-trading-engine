import { useMutation } from '@tanstack/react-query';
import { backtest as backtestApi } from '@/lib/api';
import type { BacktestRequest } from '@/types';

export function useRunBacktest() {
  return useMutation({
    mutationFn: (data: BacktestRequest) => backtestApi.run(data),
  });
}
