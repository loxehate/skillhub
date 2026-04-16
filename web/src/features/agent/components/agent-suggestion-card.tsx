import { useTranslation } from 'react-i18next'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import type { TicketAnalyzeSuggestion } from '../types'

interface AgentSuggestionCardProps {
  suggestion: TicketAnalyzeSuggestion
  onApply?: (suggestion: TicketAnalyzeSuggestion) => void
}

export function AgentSuggestionCard({ suggestion, onApply }: AgentSuggestionCardProps) {
  const { t } = useTranslation()

  return (
    <Card className="space-y-4 p-4">
      <div className="space-y-1">
        <h4 className="font-semibold">{t('agent.latestSuggestionTitle', { defaultValue: 'Analysis Suggestion' })}</h4>
        {suggestion.summary ? (
          <p className="text-sm text-muted-foreground">{suggestion.summary}</p>
        ) : null}
      </div>

      <div className="grid gap-3 text-sm md:grid-cols-2">
        {suggestion.suggestedTitle ? (
          <div>
            <div className="text-muted-foreground">{t('tickets.fieldTitle')}</div>
            <div>{suggestion.suggestedTitle}</div>
          </div>
        ) : null}
        {suggestion.mode ? (
          <div>
            <div className="text-muted-foreground">{t('tickets.fieldMode')}</div>
            <div>{t(`tickets.mode.${suggestion.mode}`, { defaultValue: suggestion.mode })}</div>
          </div>
        ) : null}
        {typeof suggestion.amount === 'number' ? (
          <div>
            <div className="text-muted-foreground">{t('tickets.fieldReward')}</div>
            <div>{suggestion.amount}{t('tickets.amountUnit')}</div>
          </div>
        ) : null}
        {suggestion.namespace ? (
          <div>
            <div className="text-muted-foreground">{t('tickets.fieldNamespace')}</div>
            <div>{suggestion.namespace}</div>
          </div>
        ) : null}
      </div>

      {suggestion.acceptanceCriteria?.length ? (
        <div className="space-y-1 text-sm">
          <div className="text-muted-foreground">{t('agent.acceptanceCriteria', { defaultValue: 'Acceptance Criteria' })}</div>
          <ul className="list-disc pl-5">
            {suggestion.acceptanceCriteria.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {suggestion.riskPoints?.length ? (
        <div className="space-y-1 text-sm">
          <div className="text-muted-foreground">{t('agent.riskPoints', { defaultValue: 'Risk Points' })}</div>
          <ul className="list-disc pl-5">
            {suggestion.riskPoints.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {onApply ? (
        <div className="flex justify-end">
          <Button type="button" variant="outline" onClick={() => onApply(suggestion)}>
            {t('agent.applySuggestion', { defaultValue: 'Apply Suggestion' })}
          </Button>
        </div>
      ) : null}
    </Card>
  )
}
