package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.ticket.TeamMember;
import com.iflytek.skillhub.domain.ticket.TeamMemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamMemberJpaRepository extends JpaRepository<TeamMember, Long>, TeamMemberRepository {
    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, String userId);
    List<TeamMember> findByTeamId(Long teamId);
    List<TeamMember> findByUserId(String userId);
}
