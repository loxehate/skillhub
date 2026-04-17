import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Textarea } from '@/shared/ui/textarea'
import type { AgentChatContext, AgentMode, TicketAnalyzeSuggestion } from '../types'
import { useAgentChat } from '../use-agent-chat'
import { AgentSuggestionCard } from './agent-suggestion-card'

interface AgentChatPanelProps {
  mode: AgentMode
  context: AgentChatContext
  initialPrompt?: string
  autoRun?: boolean
  onApplySuggestion?: (suggestion: TicketAnalyzeSuggestion) => void
}

export function AgentChatPanel({
  mode,
  context,
  initialPrompt,
  autoRun = false,
  onApplySuggestion,
}: AgentChatPanelProps) {
  const { t } = useTranslation()
  const [input, setInput] = useState(initialPrompt ?? '')
  const hasStartedRef = useRef(false)
  const messagesContainerRef = useRef<HTMLDivElement | null>(null)
  const { messages, isStreaming, send, interrupt } = useAgentChat({
    onSuggestion: onApplySuggestion,
  })

  useEffect(() => {
    setInput(initialPrompt ?? '')
  }, [initialPrompt])

  useEffect(() => {
    if (!autoRun || !initialPrompt || hasStartedRef.current) {
      return
    }
    hasStartedRef.current = true
    void send({
      message: initialPrompt,
      mode,
      context,
    })
  }, [autoRun, context, initialPrompt, mode, send])

  useEffect(() => {
    const container = messagesContainerRef.current
    if (!container) {
      return
    }
    container.scrollTop = container.scrollHeight
  }, [messages, isStreaming])

  const handleSend = async () => {
    if (!input.trim()) {
      return
    }
    await send({
      message: input.trim(),
      mode,
      context,
    })
  }

  return (
    <Card className="space-y-4 border-none bg-transparent p-0 shadow-none">
      <div
        ref={messagesContainerRef}
        className="max-h-[32rem] space-y-3 overflow-auto rounded-2xl border border-border/60 bg-muted/20 p-4"
      >
        {messages.map((message) => {
          if (message.role === 'suggestion') {
            return <AgentSuggestionCard key={message.id} suggestion={message.suggestion} />
          }

          if (message.role === 'tool') {
            return (
              <div key={message.id} className="flex justify-center">
                <div className="rounded-full border border-border/60 bg-background px-3 py-1.5 text-xs text-muted-foreground shadow-sm">
                  [{message.toolName}] {message.detail ?? message.status}
                </div>
              </div>
            )
          }

          if (message.role === 'error') {
            return (
              <div key={message.id} className="flex justify-center">
                <div className="max-w-[85%] rounded-2xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
                  {message.content}
                </div>
              </div>
            )
          }

          const isUser = message.role === 'user'

          return (
            <div key={message.id} className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm shadow-sm ${
                isUser
                  ? 'bg-primary text-primary-foreground'
                  : 'border border-border/60 bg-background text-foreground'
              }`}
              >
                <div className={`mb-1 text-[11px] font-medium uppercase tracking-wide ${
                  isUser ? 'text-primary-foreground/75' : 'text-muted-foreground'
                }`}
                >
                  {isUser
                    ? t('agent.userLabel', { defaultValue: 'You' })
                    : t('agent.assistantLabel', { defaultValue: 'Assistant' })}
                </div>
                <div className="whitespace-pre-wrap break-words">{message.content}</div>
              </div>
            </div>
          )
        })}
      </div>

      <div className="space-y-3">
        <Textarea
          value={input}
          onChange={(event) => setInput(event.target.value)}
          rows={4}
          placeholder={t('agent.inputPlaceholder', { defaultValue: 'Ask the assistant anything about SkillHub.' })}
        />
        <div className="flex justify-end gap-2">
          {isStreaming ? (
            <Button type="button" variant="outline" onClick={interrupt}>
              {t('agent.stopAction', { defaultValue: 'Stop' })}
            </Button>
          ) : null}
          <Button type="button" onClick={() => void handleSend()} disabled={isStreaming || !input.trim()}>
            {isStreaming
              ? t('agent.analyzing', { defaultValue: 'Analyzing...' })
              : t('agent.sendAction', { defaultValue: 'Send' })}
          </Button>
        </div>
      </div>
    </Card>
  )
}
