import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Ticket, TicketComment, TicketCreateRequest, TicketSubmitSkillResponse, TicketUpdateRequest } from '@/api/types'
import { ticketApi } from '@/api/client'

async function listTickets(namespace?: string): Promise<Ticket[]> {
  return ticketApi.list({ namespace })
}

async function getTicket(ticketId: number): Promise<Ticket> {
  return ticketApi.get(ticketId)
}

async function createTicket(request: TicketCreateRequest): Promise<Ticket> {
  return ticketApi.create(request)
}

async function updateTicket(ticketId: number, request: TicketUpdateRequest): Promise<Ticket> {
  return ticketApi.update(ticketId, request)
}

async function listTicketComments(ticketId: number): Promise<TicketComment[]> {
  return ticketApi.listComments(ticketId)
}

export function useTickets(namespace?: string) {
  return useQuery({
    queryKey: ['tickets', namespace ?? 'all'],
    queryFn: () => listTickets(namespace),
  })
}

export function useTicketDetail(ticketId?: number) {
  return useQuery({
    queryKey: ['tickets', ticketId],
    queryFn: () => getTicket(ticketId as number),
    enabled: Number.isFinite(ticketId),
  })
}

export function useCreateTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createTicket,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useUpdateTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ticketId, request }: { ticketId: number; request: TicketUpdateRequest }) => updateTicket(ticketId, request),
    onSuccess: (ticket) => {
      queryClient.setQueryData(['tickets', ticket.id], ticket)
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useCancelTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticketId: number) => ticketApi.cancel(ticketId),
    onSuccess: (_data, ticketId) => {
      queryClient.removeQueries({ queryKey: ['tickets', ticketId], exact: true })
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useTicketComments(ticketId?: number) {
  return useQuery({
    queryKey: ['tickets', ticketId, 'comments'],
    queryFn: () => listTicketComments(ticketId as number),
    enabled: Number.isFinite(ticketId),
  })
}

export function useAddTicketComment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ticketId, content }: { ticketId: number; content: string }) => ticketApi.addComment(ticketId, content),
    onSuccess: (comment) => {
      queryClient.invalidateQueries({ queryKey: ['tickets', comment.ticketId, 'comments'] })
    },
  })
}

export function useClaimTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticketId: number) => ticketApi.claim(ticketId),
    onSuccess: (ticket) => {
      queryClient.setQueryData(['tickets', ticket.id], ticket)
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useStartTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticketId: number) => ticketApi.start(ticketId),
    onSuccess: (ticket) => {
      queryClient.setQueryData(['tickets', ticket.id], ticket)
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useSubmitTicketReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticketId: number) => ticketApi.submitForReview(ticketId),
    onSuccess: (ticket) => {
      queryClient.setQueryData(['tickets', ticket.id], ticket)
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useCompleteTicketReview() {
  return useSubmitTicketReview()
}

export function useRejectTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ticketId, comment }: { ticketId: number; comment?: string }) => ticketApi.reject(ticketId, comment),
    onSuccess: (ticket) => {
      queryClient.setQueryData(['tickets', ticket.id], ticket)
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}

export function useSubmitTicketSkill() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (params: { ticketId: number; file: File; visibility: string; confirmWarnings?: boolean }) =>
      ticketApi.submitSkill(params),
    onSuccess: (response: TicketSubmitSkillResponse) => {
      queryClient.invalidateQueries({ queryKey: ['tickets', response.ticketId] })
      queryClient.invalidateQueries({ queryKey: ['tickets'] })
    },
  })
}
