package com.iflytek.skillhub.domain.ticket;

import java.util.List;
import java.util.Optional;

public interface TicketClaimRepository {
    List<TicketClaim> findByTicketId(Long ticketId);
    Optional<TicketClaim> findByTicketIdAndStatus(Long ticketId, TicketClaimStatus status);
    List<TicketClaim> findByUserIdAndStatus(String userId, TicketClaimStatus status);
    List<TicketClaim> findByTeamIdInAndStatus(List<Long> teamIds, TicketClaimStatus status);
    TicketClaim save(TicketClaim claim);
}
