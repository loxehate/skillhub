import { useTranslation } from 'react-i18next'
import type { AgentChatContext } from '../types'
import { AgentChatPanel } from './agent-chat-panel'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/shared/ui/dialog'

interface AiAssistantDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  context?: AgentChatContext
}

export function AiAssistantDialog({
  open,
  onOpenChange,
  context,
}: AiAssistantDialogProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[88vh] w-[min(96vw,56rem)] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {t('agent.assistantTitle', { defaultValue: 'AI Assistant' })}
          </DialogTitle>
          <DialogDescription>
            {t('agent.assistantDescription', {
              defaultValue: 'Ask about platform capabilities, skill publishing, review workflow, or ticket handling.',
            })}
          </DialogDescription>
        </DialogHeader>

        <AgentChatPanel
          mode="general_chat"
          context={context ?? { source: 'general' }}
          initialPrompt=""
        />
      </DialogContent>
    </Dialog>
  )
}
