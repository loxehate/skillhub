import { useMemo, useState } from 'react'
import { useNavigate, useParams } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { useAuth } from '@/features/auth/use-auth'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Label } from '@/shared/ui/label'
import { Textarea } from '@/shared/ui/textarea'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import { UploadZone } from '@/features/publish/upload-zone'
import { toast } from '@/shared/lib/toast'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/select'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import {
  useCancelTicket,
  useClaimTicket,
  useRejectTicket,
  useStartTicket,
  useSubmitTicketReview,
  useSubmitTicketSkill,
  useTicketDetail,
} from '@/shared/hooks/use-ticket-queries'
import {
  extractPrecheckWarnings,
  isFrontmatterFailureMessage,
  isPrecheckConfirmationMessage,
  isPrecheckFailureMessage,
  isVersionExistsMessage,
} from '@/features/publish/publish-error-utils'
import { ApiError } from '@/api/client'
import type { TicketStatus } from '@/api/types'

const STATUS_TONE: Record<string, string> = {
  OPEN: 'bg-emerald-500/10 text-emerald-500 border-emerald-500/20',
  CLAIMED: 'bg-blue-500/10 text-blue-500 border-blue-500/20',
  IN_PROGRESS: 'bg-amber-500/10 text-amber-500 border-amber-500/20',
  TEAM_REVIEW: 'bg-purple-500/10 text-purple-400 border-purple-500/20',
  SUBMITTED: 'bg-slate-500/10 text-slate-500 border-slate-500/20',
  REJECTED: 'bg-red-500/10 text-red-500 border-red-500/20',
  DONE: 'bg-emerald-500/10 text-emerald-500 border-emerald-500/20',
  FAILED: 'bg-red-500/10 text-red-500 border-red-500/20',
}

export function TicketDetailPage() {
  const { id } = useParams({ from: '/dashboard/tickets/$id' })
  const ticketId = Number(id)
  const { t, i18n } = useTranslation()
  const { user } = useAuth()
  const navigate = useNavigate()
  const { data: ticket, isLoading } = useTicketDetail(ticketId)
  const { data: namespaces } = useMyNamespaces()
  const cancelMutation = useCancelTicket()
  const claimMutation = useClaimTicket()
  const startMutation = useStartTicket()
  const submitReviewMutation = useSubmitTicketReview()
  const rejectMutation = useRejectTicket()
  const submitSkillMutation = useSubmitTicketSkill()

  const [comment, setComment] = useState('')
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [visibility, setVisibility] = useState('PUBLIC')
  const [confirmAction, setConfirmAction] = useState<null | 'claim' | 'start' | 'review'>(null)
  const [warningDialogOpen, setWarningDialogOpen] = useState(false)
  const [precheckWarnings, setPrecheckWarnings] = useState<string[]>([])

  const namespaceInfo = useMemo(() => {
    if (!ticket || !namespaces) {
      return null
    }
    return namespaces.find((ns) => ns.id === ticket.namespaceId) ?? null
  }, [ticket, namespaces])

  const formatDate = (value: string) => formatLocalDateTime(value, i18n.language)
  const statusLabel = (status: TicketStatus) => t(`tickets.status.${status}`)
  const modeLabel = (mode: string) => t(`tickets.mode.${mode}`, { defaultValue: mode })

  const canClaim = ticket?.status === 'OPEN'
  const canStart = ticket?.status === 'CLAIMED'
  const canSubmitSkill = ticket?.status === 'IN_PROGRESS'
  const canSubmitReview = ticket?.status === 'IN_PROGRESS' || ticket?.status === 'TEAM_REVIEW'
  const canReject = ticket?.status === 'TEAM_REVIEW'
  const canCancel = !!ticket && ticket.status === 'OPEN' && user?.userId === ticket.creatorId

  const handleClaim = async () => {
    if (!ticket) return
    try {
      await claimMutation.mutateAsync(ticket.id)
      toast.success(t('tickets.claimSuccess'))
    } catch (error) {
      toast.error(t('tickets.claimError'), error instanceof Error ? error.message : '')
    }
  }

  const handleStart = async () => {
    if (!ticket) return
    try {
      await startMutation.mutateAsync(ticket.id)
      toast.success(t('tickets.startSuccess'))
    } catch (error) {
      toast.error(t('tickets.startError'), error instanceof Error ? error.message : '')
    }
  }

  const handleCancel = async () => {
    if (!ticket) return
    try {
      await cancelMutation.mutateAsync(ticket.id)
      toast.success(t('tickets.cancelSuccess'))
      navigate({ to: '/dashboard/tickets' })
    } catch (error) {
      toast.error(t('tickets.cancelError'), error instanceof Error ? error.message : '')
    }
  }

  const handleSubmitReview = async () => {
    if (!ticket) return
    try {
      await submitReviewMutation.mutateAsync(ticket.id)
      toast.success(t('tickets.reviewSuccess'))
    } catch (error) {
      toast.error(t('tickets.reviewError'), error instanceof Error ? error.message : '')
    }
  }

  const handleReject = async () => {
    if (!ticket) return
    if (!comment.trim()) {
      toast.error(t('tickets.rejectRequired'))
      return
    }
    try {
      await rejectMutation.mutateAsync({ ticketId: ticket.id, comment: comment.trim() })
      toast.success(t('tickets.rejectSuccess'))
      setComment('')
    } catch (error) {
      toast.error(t('tickets.rejectError'), error instanceof Error ? error.message : '')
    }
  }

  const handleFileSelect = (file: File | null) => {
    setSelectedFile(file)
    setPrecheckWarnings([])
    setWarningDialogOpen(false)
  }

  const handleSubmitSkill = async (confirmWarnings = false) => {
    if (!ticket || !selectedFile) {
      toast.error(t('tickets.submitRequired'))
      return
    }
    try {
      const result = await submitSkillMutation.mutateAsync({
        ticketId: ticket.id,
        file: selectedFile,
        visibility,
        confirmWarnings,
      })
      const skillLabel = `${result.publish.namespace}/${result.publish.slug}@${result.publish.version}`
      toast.success(t('tickets.submitSuccessTitle'), t('tickets.submitSuccessDescription', { skill: skillLabel }))
      setSelectedFile(null)
      setPrecheckWarnings([])
      setWarningDialogOpen(false)
    } catch (error) {
      if (error instanceof ApiError && error.status === 408) {
        toast.error(t('tickets.submitTimeoutTitle'), t('tickets.submitTimeoutDescription'))
        return
      }

      if (error instanceof ApiError && isVersionExistsMessage(error.serverMessage || error.message)) {
        toast.error(t('tickets.submitVersionExistsTitle'), t('tickets.submitVersionExistsDescription'))
        return
      }

      if (error instanceof ApiError && isPrecheckConfirmationMessage(error.serverMessage || error.message)) {
        setPrecheckWarnings(extractPrecheckWarnings(error.serverMessage || error.message))
        setWarningDialogOpen(true)
        return
      }

      if (error instanceof ApiError && isPrecheckFailureMessage(error.serverMessage || error.message)) {
        toast.error(t('tickets.submitPrecheckFailedTitle'), error.serverMessage || t('tickets.submitPrecheckFailedDescription'))
        return
      }

      if (error instanceof ApiError && isFrontmatterFailureMessage(error.serverMessage || error.message)) {
        toast.error(t('tickets.submitFrontmatterFailedTitle'), error.serverMessage || t('tickets.submitFrontmatterFailedDescription'))
        return
      }

      toast.error(t('tickets.submitError'), error instanceof Error ? error.message : '')
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-6 max-w-3xl animate-fade-up">
        <div className="h-10 w-48 animate-shimmer rounded-lg" />
        <div className="h-64 animate-shimmer rounded-xl" />
      </div>
    )
  }

  if (!ticket) {
    return (
      <div className="text-center py-20 animate-fade-up">
        <h2 className="text-2xl font-bold font-heading mb-2">{t('tickets.notFound')}</h2>
      </div>
    )
  }

  return (
    <div className="space-y-8 max-w-5xl mx-auto animate-fade-up">
      <DashboardPageHeader
        title={t('tickets.detailTitle')}
        subtitle={t('tickets.detailSubtitle')}
        actions={(
          <Button variant="outline" onClick={() => navigate({ to: '/dashboard/tickets' })}>
            {t('tickets.backToList')}
          </Button>
        )}
      />

      <Card className="p-6 space-y-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="space-y-2">
            <h2 className="text-2xl font-semibold font-heading">{ticket.title}</h2>
            <div className="text-sm text-muted-foreground">#{ticket.id}</div>
          </div>
          <span className={`inline-flex items-center rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] ${STATUS_TONE[ticket.status] ?? 'border-border/60 text-muted-foreground'}`}>
            {statusLabel(ticket.status)}
          </span>
        </div>
        {ticket.description && (
          <div className="rounded-xl border border-border/60 bg-secondary/30 p-4 text-sm text-muted-foreground">
            {ticket.description}
          </div>
        )}
        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground uppercase tracking-wider">{t('tickets.modeLabel')}</Label>
            <p className="font-semibold">{modeLabel(ticket.mode)}</p>
          </div>
          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground uppercase tracking-wider">{t('tickets.namespaceLabel')}</Label>
            <p className="font-semibold">
              {namespaceInfo ? `${namespaceInfo.displayName} (@${namespaceInfo.slug})` : ticket.namespaceId}
            </p>
          </div>
          {ticket.reward != null && (
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground uppercase tracking-wider">{t('tickets.rewardLabel')}</Label>
              <p className="font-semibold">{ticket.reward}</p>
            </div>
          )}
          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground uppercase tracking-wider">{t('tickets.creatorLabel')}</Label>
            <p className="font-semibold">{ticket.creatorId}</p>
          </div>
          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground uppercase tracking-wider">{t('tickets.updatedAtLabel')}</Label>
            <p className="font-semibold text-muted-foreground">{formatDate(ticket.updatedAt)}</p>
          </div>
        </div>
      </Card>

      <Card className="p-6 space-y-6">
        <h3 className="text-lg font-semibold font-heading">{t('tickets.actionsTitle')}</h3>
        {canClaim && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">{t('tickets.claimHint')}</p>
            <Button
              onClick={() => setConfirmAction('claim')}
              disabled={claimMutation.isPending}
            >
              {claimMutation.isPending ? t('tickets.claiming') : t('tickets.claimAction')}
            </Button>
          </div>
        )}

        {canCancel && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">{t('tickets.cancelHint')}</p>
            <Button
              variant="destructive"
              onClick={handleCancel}
              disabled={cancelMutation.isPending}
            >
              {cancelMutation.isPending ? t('tickets.cancelling') : t('tickets.cancelAction')}
            </Button>
          </div>
        )}

        {canStart && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">{t('tickets.startHint')}</p>
            <Button onClick={() => setConfirmAction('start')} disabled={startMutation.isPending}>
              {startMutation.isPending ? t('tickets.starting') : t('tickets.startAction')}
            </Button>
          </div>
        )}

        {canSubmitSkill && (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label className="text-sm font-semibold font-heading">{t('tickets.submitSkill')}</Label>
              <UploadZone
                key={selectedFile ? `${selectedFile.name}-${selectedFile.lastModified}` : 'empty'}
                onFileSelect={handleFileSelect}
                disabled={submitSkillMutation.isPending}
              />
              {selectedFile && (
                <div className="flex items-center justify-between gap-3 rounded-lg border border-border/60 bg-secondary/30 px-4 py-3">
                  <div className="min-w-0 text-sm text-muted-foreground">
                    {selectedFile.name} ({(selectedFile.size / 1024).toFixed(1)} KB)
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => handleFileSelect(null)}
                    disabled={submitSkillMutation.isPending}
                  >
                    {t('tickets.removeSelectedFile')}
                  </Button>
                </div>
              )}
            </div>
            <div className="space-y-2">
              <Label className="text-sm font-semibold font-heading">{t('tickets.submitVisibility')}</Label>
              <Select value={visibility} onValueChange={setVisibility}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="PUBLIC">{t('tickets.visibilityPublic')}</SelectItem>
                  <SelectItem value="NAMESPACE_ONLY">
                    {namespaceInfo?.type === 'GLOBAL'
                      ? t('tickets.visibilityLoggedIn')
                      : t('tickets.visibilityNamespace')}
                  </SelectItem>
                  <SelectItem value="PRIVATE">{t('tickets.visibilityPrivate')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <Button
              onClick={() => handleSubmitSkill(false)}
              disabled={!selectedFile || submitSkillMutation.isPending}
            >
              {submitSkillMutation.isPending ? t('tickets.submitting') : t('tickets.submitAction')}
            </Button>
          </div>
        )}

        {canSubmitReview && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">{t('tickets.reviewHint')}</p>
            <Button
              variant="outline"
              onClick={() => setConfirmAction('review')}
              disabled={submitReviewMutation.isPending}
            >
              {submitReviewMutation.isPending ? t('tickets.reviewing') : t('tickets.submitForReview')}
            </Button>
          </div>
        )}

        {canReject && (
          <div className="space-y-3">
            <Label htmlFor="ticket-reject-comment" className="text-sm font-semibold font-heading">{t('tickets.rejectLabel')}</Label>
            <Textarea
              id="ticket-reject-comment"
              value={comment}
              onChange={(event) => setComment(event.target.value)}
              placeholder={t('tickets.rejectPlaceholder')}
              rows={4}
            />
            <Button
              variant="destructive"
              onClick={handleReject}
              disabled={rejectMutation.isPending}
            >
              {rejectMutation.isPending ? t('tickets.rejecting') : t('tickets.rejectAction')}
            </Button>
          </div>
        )}

        {!canClaim && !canCancel && !canStart && !canSubmitSkill && !canSubmitReview && !canReject && (
          <div className="rounded-xl border border-dashed border-border/70 p-6 text-center text-sm text-muted-foreground">
            {t('tickets.noActions')}
          </div>
        )}
      </Card>

      <ConfirmDialog
        open={confirmAction === 'claim'}
        onOpenChange={(open) => setConfirmAction(open ? 'claim' : null)}
        title={t('tickets.claimConfirmTitle')}
        description={t('tickets.claimConfirmDescription')}
        confirmText={t('tickets.claimAction')}
        onConfirm={handleClaim}
      />

      <ConfirmDialog
        open={confirmAction === 'start'}
        onOpenChange={(open) => setConfirmAction(open ? 'start' : null)}
        title={t('tickets.startConfirmTitle')}
        description={t('tickets.startConfirmDescription')}
        confirmText={t('tickets.startAction')}
        onConfirm={handleStart}
      />

      <ConfirmDialog
        open={confirmAction === 'review'}
        onOpenChange={(open) => setConfirmAction(open ? 'review' : null)}
        title={t('tickets.reviewConfirmTitle')}
        description={t('tickets.reviewConfirmDescription')}
        confirmText={t('tickets.submitForReview')}
        onConfirm={handleSubmitReview}
      />

      <ConfirmDialog
        open={warningDialogOpen}
        onOpenChange={setWarningDialogOpen}
        title={t('tickets.submitWarningTitle')}
        description={(
          <div className="space-y-3 text-left">
            <p>{t('tickets.submitWarningDescription')}</p>
            {precheckWarnings.length > 0 && (
              <ul className="list-disc space-y-1 pl-5">
                {precheckWarnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        confirmText={t('tickets.submitWarningContinue')}
        cancelText={t('tickets.submitWarningCancel')}
        onConfirm={() => handleSubmitSkill(true)}
      />
    </div>
  )
}
