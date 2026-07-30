import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { companyKnowledgeAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

export function useCompanyKnowledge(connectionId) {
  return useQuery({
    queryKey: queryKeys.companyKnowledge.list(connectionId),
    queryFn: () => companyKnowledgeAPI.list(connectionId),
    enabled: Boolean(connectionId),
  })
}

export function useCreateCompanyKnowledge() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: companyKnowledgeAPI.create,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.companyKnowledge.all(data.connectionId) })
      }
    },
  })
}

export function useUpdateCompanyKnowledge() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ entryId, payload }) => companyKnowledgeAPI.update(entryId, payload),
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.companyKnowledge.all(data.connectionId) })
      }
    },
  })
}

export function useDeleteCompanyKnowledge() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ entryId, connectionId }) => companyKnowledgeAPI.delete(entryId, connectionId),
    onSuccess: (_, variables) => {
      if (variables?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.companyKnowledge.all(variables.connectionId) })
      }
    },
  })
}
