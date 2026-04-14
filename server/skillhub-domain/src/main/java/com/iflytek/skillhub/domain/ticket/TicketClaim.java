package com.iflytek.skillhub.domain.ticket;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "ticket_claim")
public class TicketClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "team_id")
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketClaimStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketClaim() {
    }

    public TicketClaim(Long ticketId, String userId, Long teamId, TicketClaimStatus status) {
        this.ticketId = ticketId;
        this.userId = userId;
        this.teamId = teamId;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now(Clock.systemUTC());
    }

    public Long getId() {
        return id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public String getUserId() {
        return userId;
    }

    public Long getTeamId() {
        return teamId;
    }

    public TicketClaimStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
