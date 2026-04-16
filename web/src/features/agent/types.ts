export type AgentMode = 'general_chat' | 'ticket_analyze' | 'ticket_assistant'

export interface TicketAnalyzeSuggestion {
  summary?: string
  suggestedTitle?: string
  mode?: 'BOUNTY' | 'ASSIGN'
  namespace?: string
  amount?: number
  completenessIssues?: string[]
  rationalityAssessment?: string
  estimatedComplexity?: string
  estimatedEffort?: string
  suggestedRewardRange?: string
  similarSkills?: string[]
  developmentOutline?: string[]
  acceptanceCriteria?: string[]
  riskPoints?: string[]
  clarificationQuestions?: string[]
}

export type AgentMessage =
  | {
      id: string
      role: 'user'
      content: string
      createdAt: string
    }
  | {
      id: string
      role: 'assistant'
      content: string
      streaming?: boolean
      createdAt: string
    }
  | {
      id: string
      role: 'tool'
      toolName: string
      status: 'running' | 'success' | 'error'
      detail?: string
      createdAt: string
    }
  | {
      id: string
      role: 'suggestion'
      suggestion: TicketAnalyzeSuggestion
      createdAt: string
    }
  | {
      id: string
      role: 'error'
      content: string
      createdAt: string
    }

export interface AgentChatContext {
  source: 'ticket_create' | 'ticket_detail' | 'general'
  ticketDraft?: {
    title?: string
    description?: string
    namespace?: string
    mode?: string
  }
  user?: {
    userId?: string
  }
}

export interface AgentChatRequest {
  session_id?: string
  message: string
  mode: AgentMode
  context: AgentChatContext
}
