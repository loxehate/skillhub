package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.ticket.Team;
import com.iflytek.skillhub.domain.ticket.TeamRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamJpaRepository extends JpaRepository<Team, Long>, TeamRepository {
    List<Team> findByNamespaceId(Long namespaceId);
    List<Team> findByOwnerId(String ownerId);
}
