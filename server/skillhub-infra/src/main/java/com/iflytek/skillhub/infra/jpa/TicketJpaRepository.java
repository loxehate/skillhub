package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.ticket.Ticket;
import com.iflytek.skillhub.domain.ticket.TicketRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketJpaRepository extends JpaRepository<Ticket, Long>, TicketRepository {
    List<Ticket> findByCreatorId(String creatorId);
    List<Ticket> findByNamespaceId(Long namespaceId);
    List<Ticket> findByIdIn(List<Long> ids);
    Optional<Ticket> findBySubmitSkillId(Long skillId);
    Optional<Ticket> findBySubmitSkillVersionId(Long versionId);
}
