import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Ticket } from '@/api/types'
import { useAuth } from '@/features/auth/use-auth'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { useUpdateTicket } from '@/shared/hooks/use-ticket-queries'
import { toast } from '@/shared/lib/toast'
import { Button } from '@/shared/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/shared/ui/dialog'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { Textarea } from '@/shared/ui/textarea'

interface EditTicketDialogProps {
  ticket: Ticket
  children: React.ReactNode
}

export function EditTicketDialog({ ticket, children }: EditTicketDialogProps) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const { data: namespaces } = useMyNamespaces()
  const updateTicketMutation = useUpdateTicket()
  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState(ticket.title)
  const [description, setDescription] = useState(ticket.description ?? '')
  const [mode, setMode] = useState(ticket.mode)
  const [reward, setReward] = useState(ticket.reward != null ? String(ticket.reward) : '')
  const [namespace, setNamespace] = useState('')

  const allowedNamespaces = useMemo(() => (namespaces ?? []).filter((item) => {
    if (item.currentUserRole === 'OWNER' || item.currentUserRole === 'ADMIN') {
      return true
    }
    return user?.platformRoles?.includes('USER_ADMIN') || user?.platformRoles?.includes('SUPER_ADMIN')
  }), [namespaces, user?.platformRoles])

  useEffect(() => {
    const currentNamespace = allowedNamespaces.find((item) => item.id === ticket.namespaceId)
    setNamespace(currentNamespace?.slug ?? 'global')
  }, [allowedNamespaces, ticket.namespaceId])

  useEffect(() => {
    if (open) {
      setTitle(ticket.title)
      setDescription(ticket.description ?? '')
      setMode(ticket.mode)
      setReward(ticket.reward != null ? String(ticket.reward) : '')
      const currentNamespace = allowedNamespaces.find((item) => item.id === ticket.namespaceId)
      setNamespace(currentNamespace?.slug ?? 'global')
    }
  }, [allowedNamespaces, open, ticket])

  const handleSubmit = async () => {
    if (!title.trim()) {
      toast.error(t('tickets.editErrorTitle'), t('tickets.createRequired'))
      return
    }
    if (reward.trim() && !/^\d+$/.test(reward.trim())) {
      toast.error(t('tickets.editErrorTitle'), t('tickets.amountIntegerRequired'))
      return
    }
    try {
      await updateTicketMutation.mutateAsync({
        ticketId: ticket.id,
        request: {
          title: title.trim(),
          description: description.trim() || undefined,
          mode,
          reward: reward.trim() ? Number.parseInt(reward, 10) : undefined,
          namespace,
        },
      })
      toast.success(t('tickets.editSuccessTitle'), t('tickets.editSuccessDescription'))
      setOpen(false)
    } catch (error) {
      toast.error(t('tickets.editErrorTitle'), error instanceof Error ? error.message : '')
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="w-[min(92vw,36rem)]">
        <DialogHeader>
          <DialogTitle>{t('tickets.editTitle')}</DialogTitle>
          <DialogDescription>{t('tickets.editSubtitle')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="edit-ticket-title">{t('tickets.fieldTitle')}</Label>
            <Input
              id="edit-ticket-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder={t('tickets.fieldTitlePlaceholder')}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="edit-ticket-description">{t('tickets.fieldDescription')}</Label>
            <Textarea
              id="edit-ticket-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder={t('tickets.fieldDescriptionPlaceholder')}
              rows={4}
            />
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <Label>{t('tickets.fieldMode')}</Label>
              <Select value={mode} onValueChange={(value) => {
                setMode(value)
              }}>
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
              <Label htmlFor="edit-ticket-reward">{t('tickets.fieldReward')}</Label>
              <Input
                id="edit-ticket-reward"
                value={reward}
                onChange={(event) => setReward(event.target.value)}
                placeholder={t('tickets.fieldRewardPlaceholder')}
                type="number"
                min="0"
                step="1"
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
                {allowedNamespaces.map((ns) => (
                  <SelectItem key={ns.id} value={ns.slug}>
                    {ns.displayName} (@{ns.slug})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            {t('dialog.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={updateTicketMutation.isPending}>
            {updateTicketMutation.isPending ? t('tickets.editing') : t('tickets.editAction')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
