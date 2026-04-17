import { useCallback, useRef, useState } from 'react'
import { ApiError, buildApiUrl, getCsrfHeaders } from '@/api/client'
import type {
  AgentChatContext,
  AgentChatRequest,
  AgentMessage,
  AgentMode,
  TicketAnalyzeSuggestion,
} from './types'

function nowIso() {
  return new Date().toISOString()
}

function uid(prefix: string) {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`
}

type UseAgentChatOptions = {
  onSuggestion?: (suggestion: TicketAnalyzeSuggestion) => void
  storageKey?: string
}

export function useAgentChat(options?: UseAgentChatOptions) {
  const [messages, setMessages] = useState<AgentMessage[]>(() => {
    if (!options?.storageKey || typeof window === 'undefined') {
      return []
    }
    try {
      const raw = window.localStorage.getItem(options.storageKey)
      if (!raw) {
        return []
      }
      const parsed = JSON.parse(raw) as { messages?: AgentMessage[] }
      return Array.isArray(parsed.messages) ? parsed.messages : []
    } catch {
      return []
    }
  })
  const [isStreaming, setIsStreaming] = useState(false)
  const [sessionId, setSessionId] = useState<string | undefined>(() => {
    if (!options?.storageKey || typeof window === 'undefined') {
      return undefined
    }
    try {
      const raw = window.localStorage.getItem(options.storageKey)
      if (!raw) {
        return undefined
      }
      const parsed = JSON.parse(raw) as { sessionId?: string }
      return typeof parsed.sessionId === 'string' && parsed.sessionId ? parsed.sessionId : undefined
    } catch {
      return undefined
    }
  })
  const abortRef = useRef<AbortController | null>(null)

  const persistState = useCallback((nextMessages: AgentMessage[], nextSessionId?: string) => {
    if (!options?.storageKey || typeof window === 'undefined') {
      return
    }
    window.localStorage.setItem(options.storageKey, JSON.stringify({
      sessionId: nextSessionId,
      messages: nextMessages,
    }))
  }, [options?.storageKey])

  const appendMessage = useCallback((message: AgentMessage) => {
    setMessages((prev) => {
      const next = [...prev, message]
      persistState(next, sessionId)
      return next
    })
  }, [persistState, sessionId])

  const updateAssistantDelta = useCallback((messageId: string, delta: string) => {
    setMessages((prev) => {
      const next = prev.map((item) =>
        item.role === 'assistant' && item.id === messageId
          ? { ...item, content: item.content + delta, streaming: true }
          : item,
      )
      persistState(next, sessionId)
      return next
    })
  }, [persistState, sessionId])

  const finishAssistant = useCallback((messageId: string) => {
    setMessages((prev) => {
      const next = prev.map((item) =>
        item.role === 'assistant' && item.id === messageId
          ? { ...item, streaming: false }
          : item,
      )
      persistState(next, sessionId)
      return next
    })
  }, [persistState, sessionId])

  const send = useCallback(async (params: {
    message: string
    mode: AgentMode
    context: AgentChatContext
  }) => {
    const controller = new AbortController()
    abortRef.current = controller
    setIsStreaming(true)

    appendMessage({
      id: uid('user'),
      role: 'user',
      content: params.message,
      createdAt: nowIso(),
    })

    try {
      const response = await fetch(buildApiUrl('/api/web/agent/chat'), {
        method: 'POST',
        headers: {
          ...getCsrfHeaders({
            'Content-Type': 'application/json',
          }),
        },
        body: JSON.stringify({
          session_id: sessionId,
          message: params.message,
          mode: params.mode,
          context: params.context,
        } satisfies AgentChatRequest),
        signal: controller.signal,
      })

      if (!response.ok || !response.body) {
        throw new ApiError(`Agent request failed: HTTP ${response.status}`, response.status)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentAssistantId: string | null = null

      const flushEvent = (raw: string) => {
        const lines = raw.split('\n')
        let eventName = 'message'
        const dataLines: string[] = []

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          }
          if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim())
          }
        }

        const dataText = dataLines.join('\n')
        if (!dataText) {
          return
        }

        const parsed = JSON.parse(dataText) as unknown
        const payload = typeof parsed === 'string'
          ? JSON.parse(parsed) as Record<string, unknown>
          : parsed as Record<string, unknown>

        switch (eventName) {
          case 'session_started':
            if (typeof payload.session_id === 'string' && payload.session_id) {
              setSessionId(payload.session_id)
              persistState(messages, payload.session_id)
            }
            break

          case 'assistant_delta':
            if (!currentAssistantId) {
              currentAssistantId = uid('assistant')
              appendMessage({
                id: currentAssistantId,
                role: 'assistant',
                content: '',
                streaming: true,
                createdAt: nowIso(),
              })
            }
            if (typeof payload.delta === 'string') {
              updateAssistantDelta(currentAssistantId, payload.delta)
            }
            break

          case 'assistant_done':
            if (currentAssistantId) {
              finishAssistant(currentAssistantId)
              currentAssistantId = null
            }
            break

          case 'tool_started':
            appendMessage({
              id: uid('tool'),
              role: 'tool',
              toolName: String(payload.tool_name ?? 'tool'),
              status: 'running',
              detail: typeof payload.detail === 'string' ? payload.detail : undefined,
              createdAt: nowIso(),
            })
            break

          case 'tool_finished':
            appendMessage({
              id: uid('tool'),
              role: 'tool',
              toolName: String(payload.tool_name ?? 'tool'),
              status: 'success',
              detail: typeof payload.detail === 'string' ? payload.detail : undefined,
              createdAt: nowIso(),
            })
            break

          case 'structured_result': {
            const suggestion = payload.payload as TicketAnalyzeSuggestion
            appendMessage({
              id: uid('suggestion'),
              role: 'suggestion',
              suggestion,
              createdAt: nowIso(),
            })
            options?.onSuggestion?.(suggestion)
            break
          }

          case 'error':
            appendMessage({
              id: uid('error'),
              role: 'error',
              content: String(payload.message ?? 'Unknown agent error'),
              createdAt: nowIso(),
            })
            break

          case 'done':
            setIsStreaming(false)
            break
        }
      }

      while (true) {
        const { value, done } = await reader.read()
        if (done) {
          break
        }

        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')

        while (buffer.includes('\n\n')) {
          const index = buffer.indexOf('\n\n')
          const chunk = buffer.slice(0, index).trim()
          buffer = buffer.slice(index + 2)
          if (chunk) {
            flushEvent(chunk)
          }
        }
      }

      const finalChunk = buffer.trim()
      if (finalChunk) {
        flushEvent(finalChunk)
      }
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        appendMessage({
          id: uid('error'),
          role: 'error',
          content: error instanceof Error ? error.message : 'Agent stream failed',
          createdAt: nowIso(),
        })
      }
    } finally {
      setIsStreaming(false)
      abortRef.current = null
    }
  }, [appendMessage, finishAssistant, options, sessionId, updateAssistantDelta])

  const interrupt = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setIsStreaming(false)
  }, [])

  const reset = useCallback(() => {
    setMessages([])
    setSessionId(undefined)
    setIsStreaming(false)
    persistState([], undefined)
  }, [persistState])

  return {
    messages,
    isStreaming,
    sessionId,
    send,
    interrupt,
    reset,
  }
}
