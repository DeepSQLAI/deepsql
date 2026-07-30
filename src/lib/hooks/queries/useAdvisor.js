/**
 * TanStack Query hooks for Database Advisor API
 */

import { useQuery } from '@tanstack/react-query'
import { advisorAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

/**
 * Fetch database advisor analysis
 */
export function useAdvisorAnalysis(connectionId) {
  return useQuery({
    queryKey: queryKeys.advisor.analysis(connectionId),
    queryFn: () => advisorAPI.analyzeDatabase(connectionId),
    enabled: Boolean(connectionId),
  })
}
