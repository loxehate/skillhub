import { useTranslation } from 'react-i18next'
import { useAuth } from '@/features/auth/use-auth'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style'
import { AgentConversationPanel } from '@/features/agent/components/agent-conversation-panel'

export function AgentPage() {
  const { t } = useTranslation()
  const { user } = useAuth()

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      <DashboardPageHeader
        title={t('agent.pageTitle', { defaultValue: 'OpenClaw Agent' })}
        subtitle={t('agent.pageDescription', {
          defaultValue: 'Use the built-in AI assistant with Open WebUI-style conversation layout.',
        })}
      />

      <AgentConversationPanel
        mode="general_chat"
        context={{
          source: 'general',
          user: {
            userId: user?.userId,
          },
        }}
        storageNamespace={`general.${user?.userId ?? 'anonymous'}`}
      />
    </div>
  )
}
