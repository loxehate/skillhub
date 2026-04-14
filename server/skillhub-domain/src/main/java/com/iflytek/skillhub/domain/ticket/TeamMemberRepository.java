package com.iflytek.skillhub.domain.ticket;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository {
    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, String userId);
    List<TeamMember> findByTeamId(Long teamId);
    List<TeamMember> findByUserId(String userId);
    TeamMember save(TeamMember member);
}
