package com.iflytek.skillhub.domain.ticket;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketMode mode;

    @Column(precision = 12, scale = 2)
    private BigDecimal reward;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "creator_id", nullable = false, length = 128)
    private String creatorId;

    @Column(name = "namespace_id", nullable = false)
    private Long namespaceId;

    @Column(name = "target_team_id")
    private Long targetTeamId;

    @Column(name = "target_user_id", length = 128)
    private String targetUserId;

    @Column(name = "submit_skill_id")
    private Long submitSkillId;

    @Column(name = "submit_skill_version_id")
    private Long submitSkillVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ticket() {
    }

    public Ticket(String title,
                  String description,
                  TicketMode mode,
                  BigDecimal reward,
                  String creatorId,
                  Long namespaceId,
                  Long targetTeamId,
                  String targetUserId) {
        this.title = title;
        this.description = description;
        this.mode = mode;
        this.reward = reward;
        this.creatorId = creatorId;
        this.namespaceId = namespaceId;
        this.targetTeamId = targetTeamId;
        this.targetUserId = targetUserId;
        this.status = TicketStatus.OPEN;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now(Clock.systemUTC());
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now(Clock.systemUTC());
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TicketMode getMode() {
        return mode;
    }

    public BigDecimal getReward() {
        return reward;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public Long getNamespaceId() {
        return namespaceId;
    }

    public Long getTargetTeamId() {
        return targetTeamId;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public Long getSubmitSkillId() {
        return submitSkillId;
    }

    public Long getSubmitSkillVersionId() {
        return submitSkillVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public void setSubmitSkillId(Long submitSkillId) {
        this.submitSkillId = submitSkillId;
    }

    public void setSubmitSkillVersionId(Long submitSkillVersionId) {
        this.submitSkillVersionId = submitSkillVersionId;
    }
}
