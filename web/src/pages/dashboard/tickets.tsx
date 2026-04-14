import { useMemo, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { Card } from '@/shared/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/ui/table'
import { Button } from '@/shared/ui/button'
import { CreateTicketDialog } from '@/features/ticket/create-ticket-dialog'
import { useTickets } from '@/shared/hooks/use-ticket-queries'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import type { TicketStatus } from '@/api/types'

const STATUS_TABS: TicketStatus[] = [
  'OPEN',
  'CLAIMED',
  'IN_PROGRESS',
  'TEAM_REVIEW',
  'SUBMITTED',
  'REJECTED',
  'DONE',
  'FAILED',
]

export function TicketsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { data: tickets, isLoading } = useTickets()
  const [activeStatus, setActiveStatus] = useState<TicketStatus>('OPEN')

  const filteredTickets = useMemo(() => {
    if (!tickets) {
      return []
    }
    return tickets.filter((ticket) => ticket.status === activeStatus)
  }, [tickets, activeStatus])

  const formatDate = (dateString: string) => formatLocalDateTime(dateString, i18n.language)

  const statusLabel = (status: TicketStatus) => t(`tickets.status.${status}`)
  const modeLabel = (mode: string) => t(`tickets.mode.${mode}`, { defaultValue: mode })

  return (
    <div className="space-y-8 animate-fade-up">
      <DashboardPageHeader
        title={t('tickets.title')}
        subtitle={t('tickets.subtitle')}
        actions={(
          <CreateTicketDialog>
            <Button>{t('tickets.createAction')}</Button>
          </CreateTicketDialog>
        )}
      />

      <Card className="overflow-hidden border-border/60">
        <div className="border-b border-border/60 bg-muted/40 px-6 py-4">
          <Tabs value={activeStatus} onValueChange={(value) => setActiveStatus(value as TicketStatus)}>
            <TabsList className="flex flex-wrap gap-2 rounded-xl border-b-0 bg-muted/80 p-1 shadow-none">
              {STATUS_TABS.map((status) => (
                <TabsTrigger
                  key={status}
                  value={status}
                  className="mb-0 rounded-lg border-b-0 px-3 py-2 text-xs font-semibold uppercase tracking-[0.18em] data-[state=active]:bg-card data-[state=active]:text-foreground data-[state=active]:shadow-sm data-[state=inactive]:text-muted-foreground"
                >
                  {statusLabel(status)}
                </TabsTrigger>
              ))}
            </TabsList>
            <TabsContent value={activeStatus} className="mt-6">
              {isLoading ? (
                <div className="space-y-3">
                  {Array.from({ length: 4 }).map((_, index) => (
                    <div key={index} className="h-14 animate-shimmer rounded-xl" />
                  ))}
                </div>
              ) : filteredTickets.length > 0 ? (
                <div className="overflow-hidden rounded-xl border border-border/60">
                  <Table>
                    <TableHeader>
                      <TableRow className="bg-muted/35">
                        <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('tickets.colTitle')}</TableHead>
                        <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('tickets.colMode')}</TableHead>
                        <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('tickets.colNamespace')}</TableHead>
                        <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('tickets.colUpdated')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {filteredTickets.map((ticket) => (
                        <TableRow
                          key={ticket.id}
                          className="cursor-pointer transition-colors hover:bg-muted/30"
                          onClick={() => navigate({ to: `/dashboard/tickets/${ticket.id}` })}
                        >
                          <TableCell className="font-medium">
                            <div className="space-y-1">
                              <div>{ticket.title}</div>
                              <div className="text-xs text-muted-foreground">
                                #{ticket.id} · {statusLabel(ticket.status)}
                              </div>
                            </div>
                          </TableCell>
                          <TableCell className="text-sm">{modeLabel(ticket.mode)}</TableCell>
                          <TableCell className="text-sm text-muted-foreground">{ticket.namespaceId}</TableCell>
                          <TableCell className="text-sm text-muted-foreground">{formatDate(ticket.updatedAt)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              ) : (
                <div className="rounded-xl border border-dashed border-border/70 p-10 text-center text-muted-foreground">
                  {t('tickets.empty')}
                </div>
              )}
            </TabsContent>
          </Tabs>
        </div>
      </Card>
    </div>
  )
}
