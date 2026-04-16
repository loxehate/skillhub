import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AgentChatPanel } from '@/features/agent/components/agent-chat-panel'
import type { TicketAnalyzeSuggestion } from '@/features/agent/types'
import { useAuth } from '@/features/auth/use-auth'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { useCreateTicket } from '@/shared/hooks/use-ticket-queries'
import { toast } from '@/shared/lib/toast'
import { Button } from '@/shared/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/shared/ui/dialog'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { Textarea } from '@/shared/ui/textarea'

interface CreateTicketDialogProps {
  children: React.ReactNode
}

const DEFAULT_MODE = 'BOUNTY'
const DEFAULT_NAMESPACE = 'global'

export function CreateTicketDialog({ children }: CreateTicketDialogProps) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const { data: namespaces } = useMyNamespaces()
  const createTicketMutation = useCreateTicket()

  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [mode, setMode] = useState(DEFAULT_MODE)
  const [reward, setReward] = useState('')
  const [namespace, setNamespace] = useState(DEFAULT_NAMESPACE)
  const [analyzeOpen, setAnalyzeOpen] = useState(false)
  const [analyzeSuggestion, setAnalyzeSuggestion] = useState<TicketAnalyzeSuggestion | null>(null)

  const allowedNamespaces = (namespaces ?? []).filter((item) => {
    if (item.currentUserRole === 'OWNER' || item.currentUserRole === 'ADMIN') {
      return true
    }
    return user?.platformRoles?.includes('USER_ADMIN') || user?.platformRoles?.includes('SUPER_ADMIN')
  })

  const resetForm = () => {
    setTitle('')
    setDescription('')
    setMode(DEFAULT_MODE)
    setReward('')
    setNamespace(DEFAULT_NAMESPACE)
    setAnalyzeOpen(false)
    setAnalyzeSuggestion(null)
  }

  const applySuggestion = (suggestion: TicketAnalyzeSuggestion) => {
    if (suggestion.suggestedTitle) {
      setTitle(suggestion.suggestedTitle)
    }
    if (suggestion.mode) {
      setMode(suggestion.mode)
    }
    if (typeof suggestion.amount === 'number') {
      setReward(String(suggestion.amount))
    }
    if (suggestion.namespace) {
      setNamespace(suggestion.namespace)
    }

    toast.success(
      t('tickets.createSuccessTitle'),
      t('agent.applySuggestionSuccess', { defaultValue: 'The analysis suggestion has been applied to the form.' }),
    )
  }

  const handleSubmit = async () => {
    if (!title.trim()) {
      toast.error(t('tickets.createErrorTitle'), t('tickets.createRequired'))
      return
    }

    if (reward.trim() && !/^\d+$/.test(reward.trim())) {
      toast.error(t('tickets.createErrorTitle'), t('tickets.amountIntegerRequired'))
      return
    }

    try {
      await createTicketMutation.mutateAsync({
        title: title.trim(),
        description: description.trim() || undefined,
        mode,
        reward: reward.trim() ? Number.parseInt(reward, 10) : undefined,
        namespace,
      })

      toast.success(
        t('tickets.createSuccessTitle'),
        t('tickets.createSuccessDescription', { title: title.trim() }),
      )

      resetForm()
      setOpen(false)
    } catch (error) {
      toast.error(t('tickets.createErrorTitle'), error instanceof Error ? error.message : '')
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen)
        if (!nextOpen) {
          resetForm()
        }
      }}
    >
      <DialogTrigger asChild>{children}</DialogTrigger>

      <DialogContent className="max-h-[90vh] w-[min(92vw,42rem)] overflow-y-auto">
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
            <div className="flex items-center justify-between gap-3">
              <Label htmlFor="ticket-description">{t('tickets.fieldDescription')}</Label>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  if (!description.trim()) {
                    toast.error(
                      t('tickets.createErrorTitle'),
                      t('agent.descriptionRequired', { defaultValue: 'Please enter the description first.' }),
                    )
                    return
                  }

                  setAnalyzeOpen((prev) => !prev)
                }}
              >
                {t('agent.analyzeAction', { defaultValue: 'Analyze' })}
              </Button>
            </div>

            <Textarea
              id="ticket-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder={t('tickets.fieldDescriptionPlaceholder')}
              rows={5}
            />

            {analyzeOpen ? (
              <div className="rounded-xl border border-border/60 bg-secondary/10 p-3">
                <AgentChatPanel
                  mode="ticket_analyze"
                  autoRun
                  context={{
                    source: 'ticket_create',
                    ticketDraft: {
                      title,
                      description,
                      namespace,
                      mode,
                    },
                    user: {
                      userId: user?.userId,
                    },
                  }}
                  initialPrompt={t('agent.ticketAnalyzePrompt', {
                    defaultValue: 'Please analyze this ticket request and suggest title, mode, amount, acceptance criteria, and risks.',
                  })}
                  onApplySuggestion={(suggestion) => {
                    setAnalyzeSuggestion(suggestion)
                  }}
                />
              </div>
            ) : null}

            {analyzeSuggestion ? (
              <div className="space-y-3 rounded-xl border border-border/60 bg-secondary/20 p-4">
                <div className="space-y-1">
                  <div className="text-sm font-semibold">
                    {t('agent.latestSuggestionTitle', { defaultValue: 'Latest Analysis Suggestion' })}
                  </div>
                  {analyzeSuggestion.summary ? (
                    <p className="text-sm text-muted-foreground">{analyzeSuggestion.summary}</p>
                  ) : null}
                </div>

                <div className="grid gap-3 text-sm md:grid-cols-2">
                  {analyzeSuggestion.suggestedTitle ? (
                    <div>
                      <div className="text-muted-foreground">{t('tickets.fieldTitle')}</div>
                      <div>{analyzeSuggestion.suggestedTitle}</div>
                    </div>
                  ) : null}

                  {analyzeSuggestion.mode ? (
                    <div>
                      <div className="text-muted-foreground">{t('tickets.fieldMode')}</div>
                      <div>{t(`tickets.mode.${analyzeSuggestion.mode}`, { defaultValue: analyzeSuggestion.mode })}</div>
                    </div>
                  ) : null}

                  {typeof analyzeSuggestion.amount === 'number' ? (
                    <div>
                      <div className="text-muted-foreground">{t('tickets.fieldReward')}</div>
                      <div>{analyzeSuggestion.amount}{t('tickets.amountUnit')}</div>
                    </div>
                  ) : null}

                  {analyzeSuggestion.namespace ? (
                    <div>
                      <div className="text-muted-foreground">{t('tickets.fieldNamespace')}</div>
                      <div>{analyzeSuggestion.namespace}</div>
                    </div>
                  ) : null}
                </div>

                {analyzeSuggestion.acceptanceCriteria?.length ? (
                  <div className="space-y-1 text-sm">
                    <div className="text-muted-foreground">
                      {t('agent.acceptanceCriteria', { defaultValue: 'Acceptance Criteria' })}
                    </div>
                    <ul className="list-disc pl-5">
                      {analyzeSuggestion.acceptanceCriteria.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                {analyzeSuggestion.riskPoints?.length ? (
                  <div className="space-y-1 text-sm">
                    <div className="text-muted-foreground">
                      {t('agent.riskPoints', { defaultValue: 'Risk Points' })}
                    </div>
                    <ul className="list-disc pl-5">
                      {analyzeSuggestion.riskPoints.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                {analyzeSuggestion.clarificationQuestions?.length ? (
                  <div className="space-y-1 text-sm">
                    <div className="text-muted-foreground">
                      {t('agent.clarificationQuestions', { defaultValue: 'Clarification Questions' })}
                    </div>
                    <ul className="list-disc pl-5">
                      {analyzeSuggestion.clarificationQuestions.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                <div className="flex justify-end gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setAnalyzeSuggestion(null)}
                  >
                    {t('agent.dismissSuggestion', { defaultValue: 'Dismiss' })}
                  </Button>
                  <Button
                    type="button"
                    onClick={() => applySuggestion(analyzeSuggestion)}
                  >
                    {t('agent.applySuggestion', { defaultValue: 'Apply Suggestion' })}
                  </Button>
                </div>
              </div>
            ) : null}
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
          <Button onClick={handleSubmit} disabled={createTicketMutation.isPending}>
            {createTicketMutation.isPending ? t('tickets.creating') : t('tickets.createAction')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
