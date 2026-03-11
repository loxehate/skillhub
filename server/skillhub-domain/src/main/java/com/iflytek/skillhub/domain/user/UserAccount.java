package com.iflytek.skillhub.domain.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_account")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 256)
    private String email;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "merged_to_user_id")
    private Long mergedToUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserAccount() {}

    public UserAccount(String displayName, String email, String avatarUrl) {
        this.displayName = displayName;
        this.email = email;
        this.avatarUrl = avatarUrl;
        this.status = UserStatus.ACTIVE;
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
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public Long getMergedToUserId() { return mergedToUserId; }
    public void setMergedToUserId(Long mergedToUserId) { this.mergedToUserId = mergedToUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return this.status == UserStatus.ACTIVE; }
}
