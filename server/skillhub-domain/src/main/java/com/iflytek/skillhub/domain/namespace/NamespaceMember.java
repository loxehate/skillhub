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

    protected NamespaceMember() {}

    public NamespaceMember(Long namespaceId, Long userId, NamespaceRole role) {
        this.namespaceId = namespaceId;
        this.userId = userId;
        this.role = role;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getNamespaceId() { return namespaceId; }
    public Long getUserId() { return userId; }
    public NamespaceRole getRole() { return role; }
    public void setRole(NamespaceRole role) { this.role = role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
