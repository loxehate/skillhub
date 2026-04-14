package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.ticket.TicketClaim;
import com.iflytek.skillhub.domain.ticket.TicketClaimRepository;
import com.iflytek.skillhub.domain.ticket.TicketClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketClaimJpaRepository extends JpaRepository<TicketClaim, Long>, TicketClaimRepository {
    List<TicketClaim> findByTicketId(Long ticketId);
    Optional<TicketClaim> findByTicketIdAndStatus(Long ticketId, TicketClaimStatus status);
    List<TicketClaim> findByUserIdAndStatus(String userId, TicketClaimStatus status);
    List<TicketClaim> findByTeamIdInAndStatus(List<Long> teamIds, TicketClaimStatus status);
}
