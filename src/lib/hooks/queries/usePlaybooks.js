/**
 * TanStack Query hooks for Playbook API
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { playbookAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch all playbooks
 */
export function usePlaybooks(params = {}) {
  return useQuery({
    queryKey: queryKeys.playbooks.all(params),
    queryFn: () => playbookAPI.getAllPlaybooks(params),
  })
}

/**
 * Fetch a specific playbook
 */
export function usePlaybook(playbookId) {
  return useQuery({
    queryKey: queryKeys.playbooks.detail(playbookId),
    queryFn: () => playbookAPI.getPlaybook(playbookId),
    enabled: Boolean(playbookId),
  })
}

/**
 * Create a playbook
 */
export function useCreatePlaybook() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: playbookAPI.createPlaybook,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playbooks'] })
    },
  })
}

/**
 * Update a playbook
 */
export function useUpdatePlaybook() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ playbookId, playbookData }) =>
      playbookAPI.updatePlaybook(playbookId, playbookData),
    onSuccess: (_data, { playbookId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.playbooks.detail(playbookId),
      })
      queryClient.invalidateQueries({ queryKey: ['playbooks'] })
    },
  })
}

/**
 * Delete a playbook
 */
export function useDeletePlaybook() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: playbookAPI.deletePlaybook,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playbooks'] })
    },
  })
}

/**
 * Toggle playbook active status
 */
export function useTogglePlaybook() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: playbookAPI.togglePlaybook,
    onSuccess: (data, playbookId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.playbooks.detail(playbookId),
      })
      queryClient.invalidateQueries({ queryKey: ['playbooks'] })
    },
  })
}

/**
 * Execute a playbook
 */
export function useExecutePlaybook() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ playbookId, connectionId }) =>
      playbookAPI.executePlaybook(playbookId, connectionId),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.playbooks.runHistory(connectionId),
      })
    },
  })
}

/**
 * Fetch run history for a connection
 */
export function usePlaybookRunHistory(connectionId, limit = 50) {
  return useQuery({
    queryKey: queryKeys.playbooks.runHistory(connectionId),
    queryFn: () => playbookAPI.getRunHistory(connectionId, limit),
    enabled: Boolean(connectionId),
  })
}

/**
 * Cancel a playbook run
 */
export function useCancelPlaybookRun() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: playbookAPI.cancelRun,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playbooks', 'runs'] })
    },
  })
}

/**
 * Fetch playbook alerts
 */
export function usePlaybookAlerts(connectionId, unacknowledgedOnly = false) {
  return useQuery({
    queryKey: queryKeys.playbooks.alerts(connectionId, unacknowledgedOnly),
    queryFn: () => playbookAPI.getAlerts(connectionId, unacknowledgedOnly),
    enabled: Boolean(connectionId),
  })
}

/**
 * Acknowledge a playbook alert
 */
export function useAcknowledgePlaybookAlert() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ alertId, acknowledgedBy = 'user' }) =>
      playbookAPI.acknowledgeAlert(alertId, acknowledgedBy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playbooks', 'alerts'] })
    },
  })
}
