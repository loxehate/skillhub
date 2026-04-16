import { useTranslation } from 'react-i18next'
import { Button } from '@/shared/ui/button'

interface AiAssistantTriggerProps {
  onClick: () => void
}

export function AiAssistantTrigger({ onClick }: AiAssistantTriggerProps) {
  const { t } = useTranslation()

  return (
    <Button
      type="button"
      onClick={onClick}
      className="fixed bottom-6 right-6 z-40 rounded-full px-5 py-6 shadow-lg"
    >
      {t('agent.assistantTrigger', { defaultValue: 'AI Assistant' })}
    </Button>
  )
}
