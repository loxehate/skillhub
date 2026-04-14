package com.iflytek.skillhub.domain.ticket;

import java.util.List;
import java.util.Optional;

public interface TicketRepository {
    Optional<Ticket> findById(Long id);
    List<Ticket> findByCreatorId(String creatorId);
    List<Ticket> findByNamespaceId(Long namespaceId);
    List<Ticket> findByIdIn(List<Long> ids);
    Optional<Ticket> findBySubmitSkillId(Long skillId);
    Optional<Ticket> findBySubmitSkillVersionId(Long versionId);
    Ticket save(Ticket ticket);
}
