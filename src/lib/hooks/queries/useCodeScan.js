import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { codeScanAPI, schemaContextAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'

export function useCodeScanSources(connectionId) {
  return useQuery({
    queryKey: queryKeys.codeScan.sources(connectionId),
    queryFn: () => codeScanAPI.listSources(connectionId),
    enabled: Boolean(connectionId),
  })
}

export function useCreateCodeScanSource() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: codeScanAPI.createSource,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.codeScan.sources(data.connectionId) })
      }
    },
  })
}

export function useDeleteCodeScanSource() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: codeScanAPI.deleteSource,
    onSuccess: (_data, variables) => {
      if (variables?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.codeScan.all(variables.connectionId) })
      }
    },
  })
}

export function useUpdateCodeScanFocus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: codeScanAPI.updateFocus,
    onSuccess: (_data, variables) => {
      if (variables?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.codeScan.sources(variables.connectionId) })
      }
    },
  })
}

export function useSchemaAmbiguity(connectionId) {
  return useQuery({
    queryKey: queryKeys.schemaContext.ambiguity(connectionId),
    queryFn: () => schemaContextAPI.ambiguity(connectionId),
    enabled: Boolean(connectionId),
    staleTime: 60_000,
  })
}

export function useStartCodeScan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: codeScanAPI.startScan,
    onSuccess: (data, variables) => {
      const connectionId = variables?.connectionId || data?.connectionId
      if (connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.codeScan.all(connectionId) })
      }
    },
  })
}

export function useCodeScanJob({ jobId, connectionId, refetchInterval }) {
  return useQuery({
    queryKey: queryKeys.codeScan.job(connectionId, jobId),
    queryFn: () => codeScanAPI.getJob({ jobId, connectionId }),
    enabled: Boolean(jobId && connectionId),
    refetchInterval,
  })
}

export function useCodeScanJobStream({ jobId, connectionId }) {
  const [snapshot, setSnapshot] = useState(null)
  useEffect(() => {
    if (!jobId || !connectionId) return undefined
    const source = codeScanAPI.streamJob({ jobId, connectionId })
    const onProgress = (event) => {
      try {
        setSnapshot(JSON.parse(event.data))
      } catch (err) {
        // ignore malformed events
      }
    }
    source.addEventListener('progress', onProgress)
    source.onerror = () => {
      // EventSource will retry automatically; nothing to do here unless we want
      // to surface a transient connection state.
    }
    return () => {
      source.removeEventListener('progress', onProgress)
      source.close()
    }
  }, [jobId, connectionId])
  return snapshot
}

export function useCodeScanSuggestions({ connectionId, status = 'PENDING', page = 0, size = 50 }) {
  return useQuery({
    queryKey: queryKeys.codeScan.suggestions(connectionId, { status, page, size }),
    queryFn: () => codeScanAPI.listSuggestions({ connectionId, status, page, size }),
    enabled: Boolean(connectionId),
  })
}

// Fetch every page of suggestions for a given status. Used by the table view
// so client-side search / sort / select-all operate on the full set.
export function useAllCodeScanSuggestions({ connectionId, status = 'PENDING' }) {
  return useQuery({
    queryKey: queryKeys.codeScan.suggestions(connectionId, { status, all: true }),
    queryFn: async () => {
      const out = []
      let page = 0
      const pageSize = 200
      // Cap to keep things bounded; one project's worst-case so far is ~750.
      const maxPages = 50
      while (page < maxPages) {
        const data = await codeScanAPI.listSuggestions({
          connectionId,
          status,
          page,
          size: pageSize,
        })
        const content = data?.content || []
        out.push(...content)
        if (content.length < pageSize) break
        page += 1
      }
      return out
    },
    enabled: Boolean(connectionId),
    staleTime: 30_000,
  })
}

export function useDecideCodeScanSuggestion() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: codeScanAPI.decide,
    onSuccess: (_data, variables) => {
      if (variables?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.codeScan.all(variables.connectionId) })
        queryClient.invalidateQueries({ queryKey: queryKeys.companyKnowledge.all(variables.connectionId) })
      }
    },
  })
}

export function useBulkDecideCodeScanSuggestions() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: codeScanAPI.bulkDecide,
    onSuccess: (_data, variables) => {
      if (variables?.connectionId) {
        queryClient.invalidateQueries({ queryKey: queryKeys.codeScan.all(variables.connectionId) })
        queryClient.invalidateQueries({ queryKey: queryKeys.companyKnowledge.all(variables.connectionId) })
      }
    },
  })
}
