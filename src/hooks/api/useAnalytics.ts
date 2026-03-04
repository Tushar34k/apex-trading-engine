import { useQuery } from '@tanstack/react-query';
import { analytics as analyticsApi } from '@/lib/api';

export function useAnalytics() {
  return useQuery({
    queryKey: ['analytics-performance'],
    queryFn: analyticsApi.getPerformance,
  });
}

export function useEquityCurve() {
  return useQuery({
    queryKey: ['equity-curve'],
    queryFn: analyticsApi.getEquityCurve,
  });
}

export function useMonthlyReturns() {
  return useQuery({
    queryKey: ['monthly-returns'],
    queryFn: analyticsApi.getMonthlyReturns,
  });
}

export function useStrategyComparison() {
  return useQuery({
    queryKey: ['strategy-comparison'],
    queryFn: analyticsApi.getStrategyComparison,
  });
}
