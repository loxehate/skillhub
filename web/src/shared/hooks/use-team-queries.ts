import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Team, TeamMember, TeamRole } from '@/api/types'
import { teamApi } from '@/api/client'

async function listTeams(namespaceId: number): Promise<Team[]> {
  return teamApi.list(namespaceId)
}

async function getTeamMembers(teamId: number): Promise<TeamMember[]> {
  return teamApi.listMembers(teamId)
}

export function useTeams(namespaceId?: number) {
  return useQuery({
    queryKey: ['teams', namespaceId ?? 'none'],
    queryFn: () => listTeams(namespaceId as number),
    enabled: Number.isFinite(namespaceId),
  })
}

export function useCreateTeam() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { name: string; namespace: string }) => teamApi.create(request),
    onSuccess: (team) => {
      queryClient.invalidateQueries({ queryKey: ['teams', team.namespaceId] })
    },
  })
}

export function useTeamMembers(teamId?: number) {
  return useQuery({
    queryKey: ['teams', teamId, 'members'],
    queryFn: () => getTeamMembers(teamId as number),
    enabled: Number.isFinite(teamId),
  })
}

export function useAddTeamMember() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (params: { teamId: number; userId: string; role: TeamRole }) => teamApi.addMember(params.teamId, {
      userId: params.userId,
      role: params.role,
    }),
    onSuccess: (_member, variables) => {
      queryClient.invalidateQueries({ queryKey: ['teams', variables.teamId, 'members'] })
    },
  })
}
