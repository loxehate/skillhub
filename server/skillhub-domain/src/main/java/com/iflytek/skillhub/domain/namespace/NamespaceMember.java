package com.iflytek.skillhub.domain.namespace;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "namespace_member",
       uniqueConstraints = @UniqueConstraint(columnNames = {"namespace_id", "user_id"}))
public class NamespaceMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "namespace_id", nullable = false)
    private Long namespaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NamespaceRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NamespaceMember() {}

    public NamespaceMember(Long namespaceId, Long userId, NamespaceRole role) {
        this.namespaceId = namespaceId;
        this.userId = userId;
        this.role = role;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getNamespaceId() { return namespaceId; }
    public void setNamespaceId(Long namespaceId) { this.namespaceId = namespaceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public NamespaceRole getRole() { return role; }
    public void setRole(NamespaceRole role) { this.role = role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
