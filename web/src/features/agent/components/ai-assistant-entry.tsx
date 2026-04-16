import { useState } from 'react'
import { useAuth } from '@/features/auth/use-auth'
import { AiAssistantDialog } from './ai-assistant-dialog'
import { AiAssistantTrigger } from './ai-assistant-trigger'

export function AiAssistantEntry() {
  const { user, isLoading } = useAuth()
  const [open, setOpen] = useState(false)

  if (isLoading || !user) {
    return null
  }

  return (
    <>
      <AiAssistantTrigger onClick={() => setOpen(true)} />
      <AiAssistantDialog
        open={open}
        onOpenChange={setOpen}
        context={{
          source: 'general',
          user: {
            userId: user.userId,
          },
        }}
      />
    </>
  )
}
