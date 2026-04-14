import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/shared/ui/dialog'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { useCreateTeam } from '@/shared/hooks/use-team-queries'
import { toast } from '@/shared/lib/toast'

interface CreateTeamDialogProps {
  children: React.ReactNode
}

export function CreateTeamDialog({ children }: CreateTeamDialogProps) {
  const { t } = useTranslation()
  const { data: namespaces } = useMyNamespaces()
  const createTeamMutation = useCreateTeam()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [namespace, setNamespace] = useState('')

  const namespaceOptions = useMemo(() => namespaces ?? [], [namespaces])

  const resetForm = () => {
    setName('')
    setNamespace('')
  }

  const handleSubmit = async () => {
    if (!name.trim() || !namespace) {
      toast.error(t('teams.createErrorTitle'), t('teams.createRequired'))
      return
    }
    try {
      await createTeamMutation.mutateAsync({
        name: name.trim(),
        namespace,
      })
      toast.success(t('teams.createSuccessTitle'), t('teams.createSuccessDescription', { name: name.trim() }))
      resetForm()
      setOpen(false)
    } catch (error) {
      toast.error(t('teams.createErrorTitle'), error instanceof Error ? error.message : '')
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
      <DialogContent className="w-[min(92vw,32rem)]">
        <DialogHeader>
          <DialogTitle>{t('teams.createTitle')}</DialogTitle>
          <DialogDescription>{t('teams.createSubtitle')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="team-name">{t('teams.fieldName')}</Label>
            <Input
              id="team-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder={t('teams.fieldNamePlaceholder')}
            />
          </div>
          <div className="space-y-2">
            <Label>{t('teams.fieldNamespace')}</Label>
            <Select value={namespace} onValueChange={setNamespace}>
              <SelectTrigger>
                <SelectValue placeholder={t('teams.fieldNamespacePlaceholder')} />
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
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            {t('dialog.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={createTeamMutation.isPending}>
            {createTeamMutation.isPending ? t('teams.creating') : t('teams.createAction')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
