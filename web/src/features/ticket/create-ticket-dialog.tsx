import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/shared/ui/dialog'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Textarea } from '@/shared/ui/textarea'
import { Label } from '@/shared/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { useCreateTicket } from '@/shared/hooks/use-ticket-queries'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { useTeams } from '@/shared/hooks/use-team-queries'
import { toast } from '@/shared/lib/toast'

interface CreateTicketDialogProps {
  children: React.ReactNode
}

const DEFAULT_MODE = 'BOUNTY'

export function CreateTicketDialog({ children }: CreateTicketDialogProps) {
  const { t } = useTranslation()
  const { data: namespaces } = useMyNamespaces()
  const createTicketMutation = useCreateTicket()
  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [mode, setMode] = useState(DEFAULT_MODE)
  const [reward, setReward] = useState('')
  const [namespace, setNamespace] = useState('')
  const [targetTeamId, setTargetTeamId] = useState('')
  const [targetUserId, setTargetUserId] = useState('')

  const namespaceOptions = useMemo(() => namespaces ?? [], [namespaces])
  const selectedNamespace = useMemo(
    () => namespaceOptions.find((item) => item.slug === namespace),
    [namespace, namespaceOptions],
  )
  const { data: teams } = useTeams(selectedNamespace?.id)
  const teamOptions = useMemo(() => teams ?? [], [teams])

  const resetForm = () => {
    setTitle('')
    setDescription('')
    setMode(DEFAULT_MODE)
    setReward('')
    setNamespace('')
    setTargetTeamId('')
    setTargetUserId('')
  }

  const handleSubmit = async () => {
    if (!title.trim() || !namespace) {
      toast.error(t('tickets.createErrorTitle'), t('tickets.createRequired'))
      return
    }
    try {
      await createTicketMutation.mutateAsync({
        title: title.trim(),
        description: description.trim() || undefined,
        mode,
        reward: reward.trim() ? Number(reward) : undefined,
        namespace,
        targetTeamId: targetTeamId.trim() ? Number(targetTeamId) : undefined,
        targetUserId: targetUserId.trim() || undefined,
      })
      toast.success(t('tickets.createSuccessTitle'), t('tickets.createSuccessDescription', { title: title.trim() }))
      resetForm()
      setOpen(false)
    } catch (error) {
      toast.error(t('tickets.createErrorTitle'), error instanceof Error ? error.message : '')
    }
  }

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => {
      setOpen(nextOpen)
      if (!nextOpen) {
        resetForm()
      }
    }}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="w-[min(92vw,36rem)]">
        <DialogHeader>
          <DialogTitle>{t('tickets.createTitle')}</DialogTitle>
          <DialogDescription>{t('tickets.createSubtitle')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="ticket-title">{t('tickets.fieldTitle')}</Label>
            <Input
              id="ticket-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder={t('tickets.fieldTitlePlaceholder')}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="ticket-description">{t('tickets.fieldDescription')}</Label>
            <Textarea
              id="ticket-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder={t('tickets.fieldDescriptionPlaceholder')}
              rows={4}
            />
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <Label>{t('tickets.fieldMode')}</Label>
              <Select value={mode} onValueChange={setMode}>
                <SelectTrigger>
                  <SelectValue placeholder={t('tickets.fieldModePlaceholder')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="BOUNTY">{t('tickets.modeBounty')}</SelectItem>
                  <SelectItem value="ASSIGN">{t('tickets.modeAssign')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="ticket-reward">{t('tickets.fieldReward')}</Label>
              <Input
                id="ticket-reward"
                value={reward}
                onChange={(event) => setReward(event.target.value)}
                placeholder={t('tickets.fieldRewardPlaceholder')}
                type="number"
                min="0"
                step="0.01"
              />
            </div>
          </div>
          <div className="space-y-2">
            <Label>{t('tickets.fieldNamespace')}</Label>
            <Select value={namespace} onValueChange={setNamespace}>
              <SelectTrigger>
                <SelectValue placeholder={t('tickets.fieldNamespacePlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                {namespaceOptions.map((ns) => (
                  <SelectItem key={ns.id} value={ns.slug}>
                    {ns.displayName} (@{ns.slug})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <Label>{t('tickets.fieldTargetTeam')}</Label>
              <Select
                value={targetTeamId || 'none'}
                onValueChange={(value) => setTargetTeamId(value === 'none' ? '' : value)}
                disabled={!selectedNamespace}
              >
                <SelectTrigger id="ticket-team">
                  <SelectValue placeholder={t('tickets.fieldTargetTeamPlaceholder')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">{t('tickets.claimTeamNone')}</SelectItem>
                  {teamOptions.map((team) => (
                    <SelectItem key={team.id} value={String(team.id)}>
                      {team.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="ticket-user">{t('tickets.fieldTargetUser')}</Label>
              <Input
                id="ticket-user"
                value={targetUserId}
                onChange={(event) => setTargetUserId(event.target.value)}
                placeholder={t('tickets.fieldTargetUserPlaceholder')}
              />
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            {t('dialog.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={createTicketMutation.isPending}>
            {createTicketMutation.isPending ? t('tickets.creating') : t('tickets.createAction')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
