import { useQuery } from '@tanstack/react-query';
import { account } from '@/lib/api';

export function useAccountBalance(mode?: string) {
  return useQuery({
    queryKey: ['account-balance', mode],
    queryFn: () => account.balance(mode),
    refetchInterval: 15000,
  });
}
