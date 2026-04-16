import { useTranslation } from 'react-i18next'
import { useAuth } from '@/features/auth/use-auth'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style'
import { AgentChatPanel } from '@/features/agent/components/agent-chat-panel'
import { Card } from '@/shared/ui/card'

export function AgentPage() {
  const { t } = useTranslation()
  const { user } = useAuth()

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      <DashboardPageHeader
        title={t('agent.pageTitle', { defaultValue: 'OpenClaw Agent' })}
        subtitle={t('agent.pageDescription', { defaultValue: 'Use the built-in AI assistant for intelligent Q&A and task support.' })}
      />

      <Card className="space-y-4 p-6">
        <div className="space-y-1">
          <div className="text-lg font-semibold">
            {t('agent.assistantTitle', { defaultValue: 'AI Assistant' })}
          </div>
          <p className="text-sm text-muted-foreground">
            {t('agent.assistantDescription', {
              defaultValue: 'Ask about SkillHub publishing, review workflow, tickets, and platform usage at any time.',
            })}
          </p>
        </div>

        <AgentChatPanel
          mode="general_chat"
          context={{
            source: 'general',
            user: {
              userId: user?.userId,
            },
          }}
          initialPrompt=""
        />
      </Card>
    </div>
  )
}
