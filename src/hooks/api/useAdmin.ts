import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { admin as adminApi } from '@/lib/api';

export function useAdminUsers() {
  return useQuery({
    queryKey: ['admin-users'],
    queryFn: adminApi.listUsers,
  });
}

export function useSystemHealth() {
  return useQuery({
    queryKey: ['system-health'],
    queryFn: adminApi.getSystemHealth,
    refetchInterval: 15000,
  });
}

export function useKillSwitch() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => adminApi.killSwitch(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['bots'] });
      qc.invalidateQueries({ queryKey: ['system-health'] });
    },
  });
}

export function useResumeTrading() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => adminApi.resumeTrading(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['system-health'] }),
  });
}

export function useDisableUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => adminApi.disableUser(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-users'] }),
  });
}
