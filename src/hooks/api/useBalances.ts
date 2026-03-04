import { useQuery } from '@tanstack/react-query';
import { balances as balancesApi } from '@/lib/api';

export function useBalances() {
  return useQuery({
    queryKey: ['balances'],
    queryFn: balancesApi.list,
    refetchInterval: 15000,
  });
}
