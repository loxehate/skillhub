import { useEffect, useRef, useState } from 'react'
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
  const [input, setInput] = useState(initialPrompt ?? '')
  const hasStartedRef = useRef(false)
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
      <div className="max-h-96 space-y-3 overflow-auto">
        {messages.map((message) => {
          if (message.role === 'suggestion') {
            return <AgentSuggestionCard key={message.id} suggestion={message.suggestion} />
          }

          if (message.role === 'tool') {
            return (
              <div key={message.id} className="rounded-lg border border-border/60 px-3 py-2 text-sm text-muted-foreground">
                [{message.toolName}] {message.detail ?? message.status}
              </div>
            )
          }

          return (
            <div
              key={message.id}
              className="rounded-lg border border-border/60 bg-background px-3 py-2 text-sm"
            >
              <div className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">{message.role}</div>
              <div>{message.content}</div>
            </div>
          )
        })}
      </div>

      <div className="space-y-3">
        <Textarea
          value={input}
          onChange={(event) => setInput(event.target.value)}
          rows={4}
          placeholder="Ask the agent for analysis"
        />
        <div className="flex justify-end gap-2">
          {isStreaming ? (
            <Button type="button" variant="outline" onClick={interrupt}>
              Stop
            </Button>
          ) : null}
          <Button type="button" onClick={() => void handleSend()} disabled={isStreaming || !input.trim()}>
            {isStreaming ? 'Analyzing...' : 'Send'}
          </Button>
        </div>
      </div>
    </Card>
  )
}
