package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.ticket.TicketComment;
import com.iflytek.skillhub.domain.ticket.TicketCommentRepository;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketCommentJpaRepository extends JpaRepository<TicketComment, Long>, TicketCommentRepository {
    List<TicketComment> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
