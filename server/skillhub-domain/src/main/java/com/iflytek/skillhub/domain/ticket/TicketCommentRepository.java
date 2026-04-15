package com.iflytek.skillhub.domain.ticket;

import java.util.List;

public interface TicketCommentRepository {
    List<TicketComment> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
    TicketComment save(TicketComment comment);
}
