import { useTranslation } from 'react-i18next'
import { getOpenWebUiRuntimeConfig } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'

interface OpenWebUiEmbedProps {
  className?: string
}

export function OpenWebUiEmbed({ className }: OpenWebUiEmbedProps) {
  const { t } = useTranslation()
  const config = getOpenWebUiRuntimeConfig()

  if (!config.enabled || !config.url) {
    return (
      <Card className="space-y-3 p-6">
        <div className="space-y-1">
          <div className="text-lg font-semibold">
            {t('agent.openWebUiNotConfiguredTitle', { defaultValue: 'Open WebUI is not configured' })}
          </div>
          <p className="text-sm text-muted-foreground">
            {t('agent.openWebUiNotConfiguredDescription', {
              defaultValue: 'Set SKILLHUB_WEB_OPENWEBUI_URL so SkillHub can embed the Open WebUI conversation page here.',
            })}
          </p>
        </div>
      </Card>
    )
  }

  return (
    <div className={className ?? 'space-y-4'}>
      <div className="flex items-center justify-between gap-3">
        <div className="space-y-1">
          <div className="text-lg font-semibold">
            {config.title || t('agent.openWebUiTitle', { defaultValue: 'Open WebUI Assistant' })}
          </div>
          <p className="text-sm text-muted-foreground">
            {t('agent.openWebUiDescription', {
              defaultValue: 'The conversation is hosted by Open WebUI, so chat history and streaming behavior follow Open WebUI session mode directly.',
            })}
          </p>
        </div>
        <Button
          type="button"
          variant="outline"
          onClick={() => window.open(config.url, '_blank', 'noopener,noreferrer')}
        >
          {t('agent.openInNewTab', { defaultValue: 'Open in New Tab' })}
        </Button>
      </div>

      <div className="overflow-hidden rounded-2xl border border-border/60 bg-background">
        <iframe
          src={config.url}
          title={config.title || 'Open WebUI'}
          className="min-h-[70vh] w-full border-0"
          allow="clipboard-read; clipboard-write"
          referrerPolicy="strict-origin-when-cross-origin"
        />
      </div>
    </div>
  )
}
