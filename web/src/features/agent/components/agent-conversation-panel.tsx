import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AgentChatContext, AgentMessage, AgentMode } from '../types'
import { AgentChatPanel } from './agent-chat-panel'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'

type LocalConversation = {
  id: string
  title: string
  updatedAt: string
  storageKey: string
  preview?: string
}

interface AgentConversationPanelProps {
  mode: AgentMode
  context: AgentChatContext
  storageNamespace: string
}

function conversationsIndexKey(namespace: string) {
  return `skillhub.agent.conversations.${namespace}`
}

function conversationStorageKey(namespace: string, id: string) {
  return `skillhub.agent.chat.${namespace}.${id}`
}

function nowIso() {
  return new Date().toISOString()
}

function uid() {
  return Math.random().toString(36).slice(2, 10)
}

function loadIndex(namespace: string): LocalConversation[] {
  if (typeof window === 'undefined') {
    return []
  }
  try {
    const raw = window.localStorage.getItem(conversationsIndexKey(namespace))
    if (!raw) {
      return []
    }
    const parsed = JSON.parse(raw) as LocalConversation[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function saveIndex(namespace: string, conversations: LocalConversation[]) {
  if (typeof window === 'undefined') {
    return
  }
  window.localStorage.setItem(conversationsIndexKey(namespace), JSON.stringify(conversations))
}

function createConversation(namespace: string): LocalConversation {
  const id = uid()
  return {
    id,
    title: 'New Chat',
    updatedAt: nowIso(),
    storageKey: conversationStorageKey(namespace, id),
    preview: '',
  }
}

function deriveTitle(messages: AgentMessage[]) {
  const firstUser = messages.find((message) => message.role === 'user')
  const text = firstUser && 'content' in firstUser ? firstUser.content.trim() : ''
  if (!text) {
    return 'New Chat'
  }
  return text.length <= 28 ? text : `${text.slice(0, 28)}...`
}

function derivePreview(messages: AgentMessage[]) {
  const latest = [...messages].reverse().find((message) => 'content' in message && typeof message.content === 'string')
  const text = latest && 'content' in latest ? latest.content.trim() : ''
  if (!text) {
    return ''
  }
  return text.length <= 80 ? text : `${text.slice(0, 80)}...`
}

export function AgentConversationPanel({
  mode,
  context,
  storageNamespace,
}: AgentConversationPanelProps) {
  const { t } = useTranslation()
  const [conversations, setConversations] = useState<LocalConversation[]>([])
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null)

  useEffect(() => {
    const loaded = loadIndex(storageNamespace)
    if (loaded.length > 0) {
      setConversations(loaded)
      setActiveConversationId(loaded[0].id)
      return
    }

    const initial = createConversation(storageNamespace)
    setConversations([initial])
    setActiveConversationId(initial.id)
    saveIndex(storageNamespace, [initial])
  }, [storageNamespace])

  const activeConversation = useMemo(
    () => conversations.find((item) => item.id === activeConversationId) ?? conversations[0] ?? null,
    [activeConversationId, conversations],
  )

  const handleNewConversation = () => {
    const next = createConversation(storageNamespace)
    setConversations((prev) => {
      const updated = [next, ...prev]
      saveIndex(storageNamespace, updated)
      return updated
    })
    setActiveConversationId(next.id)
  }

  const handleConversationStateChange = (state: { messages: AgentMessage[], sessionId?: string }) => {
    if (!activeConversation) {
      return
    }
    const updatedConversation: LocalConversation = {
      ...activeConversation,
      title: deriveTitle(state.messages),
      preview: derivePreview(state.messages),
      updatedAt: nowIso(),
    }
    setConversations((prev) => {
      const updated = [
        updatedConversation,
        ...prev.filter((item) => item.id !== updatedConversation.id),
      ]
      saveIndex(storageNamespace, updated)
      return updated
    })
  }

  if (!activeConversation) {
    return null
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[16rem,minmax(0,1fr)]">
      <Card className="space-y-3 p-3">
        <div className="flex items-center justify-between gap-2">
          <div className="text-sm font-semibold">
            {t('agent.sessionListTitle', { defaultValue: 'Conversations' })}
          </div>
          <Button type="button" size="sm" variant="outline" onClick={handleNewConversation}>
            {t('agent.newChat', { defaultValue: 'New Chat' })}
          </Button>
        </div>

        <div className="max-h-[32rem] space-y-2 overflow-auto pr-1">
          {conversations.map((conversation) => (
            <button
              key={conversation.id}
              type="button"
              onClick={() => setActiveConversationId(conversation.id)}
              className={
                conversation.id === activeConversation.id
                  ? 'w-full rounded-xl border border-primary/35 bg-primary/10 px-3 py-2 text-left'
                  : 'w-full rounded-xl border border-border/60 px-3 py-2 text-left hover:bg-secondary/35'
              }
            >
              <div className="truncate text-sm font-medium">{conversation.title}</div>
              <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                {conversation.preview || t('agent.noMessagesYet', { defaultValue: 'No messages yet' })}
              </div>
            </button>
          ))}
        </div>
      </Card>

      <AgentChatPanel
        key={activeConversation.id}
        mode={mode}
        context={context}
        storageKey={activeConversation.storageKey}
        onConversationStateChange={handleConversationStateChange}
      />
    </div>
  )
}
