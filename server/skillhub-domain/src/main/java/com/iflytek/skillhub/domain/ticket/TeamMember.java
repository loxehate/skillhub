package com.iflytek.skillhub.domain.ticket;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "team_member")
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TeamRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TeamMember() {
    }

    public TeamMember(Long teamId, String userId, TeamRole role) {
        this.teamId = teamId;
        this.userId = userId;
        this.role = role;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now(Clock.systemUTC());
    }

    public Long getId() {
        return id;
    }

    public Long getTeamId() {
        return teamId;
    }

    public String getUserId() {
        return userId;
    }

    public TeamRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
