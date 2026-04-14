package com.iflytek.skillhub.domain.ticket;

import java.util.List;
import java.util.Optional;

public interface TeamRepository {
    Optional<Team> findById(Long id);
    List<Team> findByNamespaceId(Long namespaceId);
    List<Team> findByOwnerId(String ownerId);
    Team save(Team team);
}
