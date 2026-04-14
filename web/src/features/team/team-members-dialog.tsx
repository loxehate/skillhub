import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/ui/dialog'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { useAddTeamMember, useTeamMembers } from '@/shared/hooks/use-team-queries'
import type { TeamRole } from '@/api/types'
import { toast } from '@/shared/lib/toast'

interface TeamMembersDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  teamId: number | null
  teamName?: string
}

export function TeamMembersDialog({ open, onOpenChange, teamId, teamName }: TeamMembersDialogProps) {
  const { t } = useTranslation()
  const { data: members, isLoading, refetch } = useTeamMembers(teamId ?? undefined)
  const addMemberMutation = useAddTeamMember()
  const [userId, setUserId] = useState('')
  const [role, setRole] = useState<TeamRole>('DEV')

  useEffect(() => {
    if (open && teamId) {
      void refetch()
    }
  }, [open, teamId, refetch])

  const resetForm = () => {
    setUserId('')
    setRole('DEV')
  }

  const handleAddMember = async () => {
    if (!teamId || !userId.trim()) {
      toast.error(t('teams.addMemberErrorTitle'), t('teams.addMemberRequired'))
      return
    }
    try {
      await addMemberMutation.mutateAsync({
        teamId,
        userId: userId.trim(),
        role,
      })
      toast.success(t('teams.addMemberSuccessTitle'), t('teams.addMemberSuccessDescription', { userId: userId.trim() }))
      resetForm()
    } catch (error) {
      toast.error(t('teams.addMemberErrorTitle'), error instanceof Error ? error.message : '')
    }
  }

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => {
      onOpenChange(nextOpen)
      if (!nextOpen) {
        resetForm()
      }
    }}>
      <DialogContent className="w-[min(92vw,40rem)]">
        <DialogHeader>
          <DialogTitle>{t('teams.membersTitle', { name: teamName ?? '' })}</DialogTitle>
          <DialogDescription>{t('teams.membersSubtitle')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-6">
          <div className="rounded-xl border border-border/60 p-4">
            <div className="grid gap-4 md:grid-cols-3">
              <div className="space-y-2 md:col-span-2">
                <Label htmlFor="team-member-userId">{t('teams.memberUserId')}</Label>
                <Input
                  id="team-member-userId"
                  value={userId}
                  onChange={(event) => setUserId(event.target.value)}
                  placeholder={t('teams.memberUserIdPlaceholder')}
                />
              </div>
              <div className="space-y-2">
                <Label>{t('teams.memberRole')}</Label>
                <Select value={role} onValueChange={(value) => setRole(value as TeamRole)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ADMIN">{t('teams.memberRoleAdmin')}</SelectItem>
                    <SelectItem value="DEV">{t('teams.memberRoleDev')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="mt-4 flex justify-end">
              <Button onClick={handleAddMember} disabled={addMemberMutation.isPending}>
                {addMemberMutation.isPending ? t('teams.addingMember') : t('teams.addMemberAction')}
              </Button>
            </div>
          </div>

          <div className="rounded-xl border border-border/60">
            <div className="bg-muted/40 px-4 py-3 text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">
              {t('teams.membersList')}
            </div>
            {isLoading ? (
              <div className="space-y-3 p-4">
                {Array.from({ length: 3 }).map((_, index) => (
                  <div key={index} className="h-12 animate-shimmer rounded-lg" />
                ))}
              </div>
            ) : members && members.length > 0 ? (
              <div className="divide-y divide-border/60">
                {members.map((member) => (
                  <div key={`${member.teamId}-${member.userId}`} className="flex items-center justify-between px-4 py-3 text-sm">
                    <div>
                      <div className="font-medium">{member.userId}</div>
                      <div className="text-xs text-muted-foreground">{t('teams.memberJoinedAt', { date: new Date(member.createdAt).toLocaleString() })}</div>
                    </div>
                    <span className="rounded-full border border-border/60 px-3 py-1 text-xs uppercase tracking-[0.16em]">
                      {member.role}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-6 text-center text-sm text-muted-foreground">{t('teams.membersEmpty')}</div>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('dialog.close')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
