package com.iflytek.skillhub.listener;

import com.iflytek.skillhub.domain.event.ReviewRejectedEvent;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.ticket.TicketService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TicketSkillStatusListener {

    private final TicketService ticketService;

    public TicketSkillStatusListener(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener
    public void onSkillPublished(SkillPublishedEvent event) {
        ticketService.markSkillPublished(event.skillId(), event.versionId());
    }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener
    public void onReviewRejected(ReviewRejectedEvent event) {
        ticketService.markSkillRejected(event.skillId(), event.versionId());
    }
}
