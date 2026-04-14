import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/ui/table'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue, normalizeSelectValue } from '@/shared/ui/select'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { useTeams } from '@/shared/hooks/use-team-queries'
import { CreateTeamDialog } from '@/features/team/create-team-dialog'
import { TeamMembersDialog } from '@/features/team/team-members-dialog'
import { formatLocalDateTime } from '@/shared/lib/date-time'

const EMPTY_NAMESPACE_VALUE = '__select_namespace__'

export function TeamsPage() {
  const { t, i18n } = useTranslation()
  const { data: namespaces, isLoading: isLoadingNamespaces } = useMyNamespaces()
  const [activeNamespaceId, setActiveNamespaceId] = useState<number | null>(null)
  const [selectedTeamId, setSelectedTeamId] = useState<number | null>(null)
  const [membersDialogOpen, setMembersDialogOpen] = useState(false)

  useEffect(() => {
    if (!activeNamespaceId && namespaces && namespaces.length > 0) {
      setActiveNamespaceId(namespaces[0].id)
    }
  }, [activeNamespaceId, namespaces])

  const { data: teams, isLoading: isLoadingTeams } = useTeams(activeNamespaceId ?? undefined)

  const namespaceOptions = useMemo(() => namespaces ?? [], [namespaces])
  const activeNamespace = namespaceOptions.find((ns) => ns.id === activeNamespaceId)
  const formatDate = (value: string) => formatLocalDateTime(value, i18n.language)

  return (
    <div className="space-y-8 animate-fade-up">
      <DashboardPageHeader
        title={t('teams.title')}
        subtitle={t('teams.subtitle')}
        actions={(
          <CreateTeamDialog>
            <Button>{t('teams.createAction')}</Button>
          </CreateTeamDialog>
        )}
      />

      <Card className="p-6 space-y-6">
        <div className="space-y-2">
          <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
            {t('teams.namespaceFilter')}
          </div>
          {isLoadingNamespaces ? (
            <div className="h-11 animate-shimmer rounded-lg" />
          ) : (
            <Select
              value={normalizeSelectValue(activeNamespaceId ? String(activeNamespaceId) : null) ?? EMPTY_NAMESPACE_VALUE}
              onValueChange={(value) => {
                if (value === EMPTY_NAMESPACE_VALUE) {
                  setActiveNamespaceId(null)
                } else {
                  setActiveNamespaceId(Number(value))
                }
              }}
            >
              <SelectTrigger>
                <SelectValue placeholder={t('teams.namespacePlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={EMPTY_NAMESPACE_VALUE}>{t('teams.namespacePlaceholder')}</SelectItem>
                {namespaceOptions.map((namespace) => (
                  <SelectItem key={namespace.id} value={String(namespace.id)}>
                    {namespace.displayName} (@{namespace.slug})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          {activeNamespace ? (
            <p className="text-sm text-muted-foreground">
              {t('teams.namespaceSelected', { name: activeNamespace.displayName })}
            </p>
          ) : (
            <p className="text-sm text-muted-foreground">{t('teams.namespaceEmpty')}</p>
          )}
        </div>

        {isLoadingTeams ? (
          <div className="space-y-3">
            {Array.from({ length: 4 }).map((_, index) => (
              <div key={index} className="h-14 animate-shimmer rounded-xl" />
            ))}
          </div>
        ) : teams && teams.length > 0 ? (
          <div className="overflow-hidden rounded-xl border border-border/60">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/35">
                  <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('teams.colName')}</TableHead>
                  <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('teams.colOwner')}</TableHead>
                  <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('teams.colCreated')}</TableHead>
                  <TableHead className="text-xs uppercase tracking-[0.18em] text-muted-foreground">{t('teams.colActions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {teams.map((team) => (
                  <TableRow key={team.id} className="hover:bg-muted/30">
                    <TableCell className="font-medium">{team.name}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{team.ownerId}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{formatDate(team.createdAt)}</TableCell>
                    <TableCell>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          setSelectedTeamId(team.id)
                          setMembersDialogOpen(true)
                        }}
                      >
                        {t('teams.manageMembers')}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ) : (
          <div className="rounded-xl border border-dashed border-border/70 p-10 text-center text-muted-foreground">
            {t('teams.empty')}
          </div>
        )}
      </Card>

      <TeamMembersDialog
        open={membersDialogOpen}
        onOpenChange={setMembersDialogOpen}
        teamId={selectedTeamId}
        teamName={teams?.find((team) => team.id === selectedTeamId)?.name}
      />
    </div>
  )
}
