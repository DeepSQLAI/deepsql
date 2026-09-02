/**
 * TanStack Query hooks for LLM usage and cost accounting (admin only)
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { llmUsageAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

export function useLlmUsageSummary(days = 30) {
  return useQuery({
    queryKey: queryKeys.llmUsage.summary(days),
    queryFn: () => llmUsageAPI.getSummary(days),
  })
}

export function useLlmUsageRecent({ days = 30, page = 0, size = 50 } = {}) {
  return useQuery({
    queryKey: queryKeys.llmUsage.recent(days, page, size),
    queryFn: () => llmUsageAPI.getRecent({ days, page, size }),
  })
}

export function useLlmPricing() {
  return useQuery({
    queryKey: queryKeys.llmUsage.pricing(),
    queryFn: () => llmUsageAPI.getPricing(),
  })
}

export function useUpdateLlmPricing() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ model, rates }) => llmUsageAPI.updatePricing(model, rates),
    // A rate change alters what future calls cost, so the summary is refetched too —
    // not just the pricing list the form is bound to.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['llmUsage'] }),
  })
}

export function usePurgeLlmUsage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (olderThanDays) => llmUsageAPI.purge(olderThanDays),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['llmUsage'] }),
  })
}
