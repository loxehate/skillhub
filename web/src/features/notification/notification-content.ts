import type { NotificationItem } from '@/api/types'

export type NotificationDisplay = {
  title: string
  description: string
}

type NotificationBody = {
  skillName?: string
  version?: string
  ticketTitle?: string
  status?: string
}

function parseBody(bodyJson?: string): NotificationBody {
  if (!bodyJson) {
    return {}
  }
  try {
    const parsed = JSON.parse(bodyJson)
    return typeof parsed === 'object' && parsed !== null ? (parsed as NotificationBody) : {}
  } catch {
    return {}
  }
}

function isChinese(language: string) {
  return language.toLowerCase().startsWith('zh')
}

export function resolveNotificationDisplay(item: NotificationItem, language: string): NotificationDisplay {
  const zh = isChinese(language)
  const body = parseBody(item.bodyJson)
  const skillName = body.skillName ?? ''
  const ticketTitle = body.ticketTitle ?? ''
  const version = body.version ?? ''
  const versionSuffix = version ? ` (${version})` : ''

  switch (item.eventType) {
    case 'REVIEW_SUBMITTED':
      return {
        title: zh ? '技能审核已提交' : 'Review submitted',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 已提交审核。` : `${skillName}${versionSuffix} was submitted for review.`) : '',
      }
    case 'REVIEW_APPROVED':
      return {
        title: zh ? '技能审核通过' : 'Review approved',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 已审核通过。` : `${skillName}${versionSuffix} was approved.`) : '',
      }
    case 'REVIEW_REJECTED':
      return {
        title: zh ? '技能审核驳回' : 'Review rejected',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 审核未通过。` : `${skillName}${versionSuffix} was rejected.`) : '',
      }
    case 'PROMOTION_SUBMITTED':
      return {
        title: zh ? '技能推广已提交' : 'Promotion submitted',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 已提交推广。` : `${skillName}${versionSuffix} was submitted for promotion.`) : '',
      }
    case 'PROMOTION_APPROVED':
      return {
        title: zh ? '技能推广通过' : 'Promotion approved',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 推广已通过。` : `${skillName}${versionSuffix} promotion was approved.`) : '',
      }
    case 'PROMOTION_REJECTED':
      return {
        title: zh ? '技能推广驳回' : 'Promotion rejected',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 推广未通过。` : `${skillName}${versionSuffix} promotion was rejected.`) : '',
      }
    case 'REPORT_SUBMITTED':
      return {
        title: zh ? '技能举报已提交' : 'Report submitted',
        description: skillName ? (zh ? `${skillName} 收到新的举报。` : `${skillName} received a new report.`) : '',
      }
    case 'REPORT_RESOLVED':
      return {
        title: zh ? '技能举报已处理' : 'Report resolved',
        description: skillName ? (zh ? `${skillName} 的举报已处理。` : `${skillName} report has been resolved.`) : '',
      }
    case 'SKILL_PUBLISHED':
      return {
        title: zh ? '技能发布成功' : 'Skill published',
        description: skillName ? (zh ? `${skillName}${versionSuffix} 已发布。` : `${skillName}${versionSuffix} was published.`) : '',
      }
    case 'TICKET_CREATED':
      return {
        title: zh ? '收到新工单' : 'New ticket assigned',
        description: ticketTitle ? (zh ? `工单《${ticketTitle}》已创建。` : `Ticket "${ticketTitle}" has been created.`) : '',
      }
    case 'TICKET_CLAIMED':
      return {
        title: zh ? '工单已认领' : 'Ticket claimed',
        description: ticketTitle ? (zh ? `工单《${ticketTitle}》已被认领。` : `Ticket "${ticketTitle}" has been claimed.`) : '',
      }
    case 'TICKET_REVIEW_SUBMITTED':
      return {
        title: zh ? '工单已提交评审' : 'Ticket submitted for review',
        description: ticketTitle ? (zh ? `工单《${ticketTitle}》已进入评审阶段。` : `Ticket "${ticketTitle}" entered review.`) : '',
      }
    case 'TICKET_REJECTED':
      return {
        title: zh ? '工单被驳回' : 'Ticket rejected',
        description: ticketTitle ? (zh ? `工单《${ticketTitle}》被驳回，请继续处理。` : `Ticket "${ticketTitle}" was rejected.`) : '',
      }
    case 'TICKET_SKILL_SUBMITTED':
      return {
        title: zh ? '工单已提交技能包' : 'Ticket skill submitted',
        description: ticketTitle ? (zh ? `工单《${ticketTitle}》已提交技能包。` : `A skill package was submitted for "${ticketTitle}".`) : '',
      }
    case 'TICKET_CLOSED':
      return {
        title: body.status === 'DONE'
          ? (zh ? '工单已完成' : 'Ticket completed')
          : (zh ? '工单失败' : 'Ticket failed'),
        description: ticketTitle ? (zh ? `工单《${ticketTitle}》当前状态：${body.status ?? ''}。` : `Ticket "${ticketTitle}" status: ${body.status ?? ''}.`) : '',
      }
    default:
      return {
        title: item.title,
        description: '',
      }
  }
}
