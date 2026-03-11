package com.iflytek.skillhub.domain.namespace;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "namespace")
public class Namespace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NamespaceStatus status = NamespaceStatus.ACTIVE;

    @Column(length = 512)
    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Namespace() {}

    public Namespace(String slug, String displayName, Long createdBy) {
        this.slug = slug;
        this.displayName = displayName;
        this.createdBy = createdBy;
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
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public NamespaceStatus getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
