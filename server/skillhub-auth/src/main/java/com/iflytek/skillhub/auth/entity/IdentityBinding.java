package com.iflytek.skillhub.auth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "identity_binding",
       uniqueConstraints = @UniqueConstraint(columnNames = {"provider_code", "subject"}))
public class IdentityBinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(nullable = false, length = 256)
    private String subject;

    @Column(name = "login_name", length = 128)
    private String loginName;

    @Column(name = "extra_json", columnDefinition = "jsonb")
    private String extraJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected IdentityBinding() {}

    public IdentityBinding(Long userId, String providerCode, String subject, String loginName) {
        this.userId = userId;
        this.providerCode = providerCode;
        this.subject = subject;
        this.loginName = loginName;
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
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }
    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }
}
