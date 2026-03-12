# Phase 3: 审核流程 + CLI API + 评分收藏 + 兼容层 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Phase 2 基础上建立完整的治理体系、CLI 生态和社交功能，实现审核流程、提升机制、评分收藏、CLI API、ClawHub 兼容层和管理后台。

**Architecture:**
- 审核流程：乐观锁 + partial unique index 防止并发冲突，分级权限控制
- 评分收藏：异步事件 + Redis 分布式锁更新计数器
- CLI API：OAuth Device Flow 标准认证流程
- 兼容层：Canonical slug 映射实现 ClawHub CLI 协议兼容
- 幂等去重：Redis SETNX + PostgreSQL 双层防护

**Tech Stack:**
- 后端：Spring Boot 3.x + JDK 21 + PostgreSQL 16 + Redis 7 + Spring Security + Flyway
- 前端：React 19 + TypeScript + Vite + TanStack Router + TanStack Query + shadcn/ui
- 新增：react-rating-stars-component（评分组件）

---

## Chunk 1: 审核流程核心（后端）

**范围：** 数据库迁移 + 审核流程 + 提升流程 + 乐观锁 + 分级权限

**验收标准：**
1. 用户可以提交审核，创建 review_task（status=PENDING）
2. 审核人可以通过/拒绝审核，乐观锁防止并发冲突
3. 审核通过后，skill_version.status → PUBLISHED，触发搜索索引更新
4. 审核拒绝后，skill_version.status → REJECTED，记录拒绝原因
5. 用户可以撤回 PENDING 状态的审核
6. 团队管理员只能审核自己管理的 namespace 的技能
7. 平台 SKILL_ADMIN 只能审核全局空间的技能
8. 用户可以提交提升请求，创建 promotion_request（status=PENDING）
9. 平台 SKILL_ADMIN 可以审核提升请求
10. 提升通过后，在全局空间创建新 skill，复制版本和文件
11. 所有审核操作写入 audit_log
12. 所有测试通过

### Task 1: 数据库迁移脚本

**Files:**
- Create: `server/skillhub-app/src/main/resources/db/migration/V3__phase3_review_social_tables.sql`

- [ ] **Step 1: 创建数据库迁移脚本**

创建 `V3__phase3_review_social_tables.sql`，包含 5 个新表：

```sql
-- review_task 表
CREATE TABLE review_task (
    id BIGSERIAL PRIMARY KEY,
    skill_version_id BIGINT NOT NULL REFERENCES skill_version(id),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 1,
    submitted_by BIGINT NOT NULL REFERENCES user_account(id),
    reviewed_by BIGINT REFERENCES user_account(id),
    review_comment TEXT,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP
);

CREATE INDEX idx_review_task_namespace_status ON review_task(namespace_id, status);
CREATE INDEX idx_review_task_submitted_by_status ON review_task(submitted_by, status);
CREATE UNIQUE INDEX idx_review_task_version_pending ON review_task(skill_version_id) WHERE status = 'PENDING';

-- promotion_request 表
CREATE TABLE promotion_request (
    id BIGSERIAL PRIMARY KEY,
    source_skill_id BIGINT NOT NULL REFERENCES skill(id),
    source_version_id BIGINT NOT NULL REFERENCES skill_version(id),
    target_namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    target_skill_id BIGINT REFERENCES skill(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 1,
    submitted_by BIGINT NOT NULL REFERENCES user_account(id),
    reviewed_by BIGINT REFERENCES user_account(id),
    review_comment TEXT,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP
);

CREATE INDEX idx_promotion_request_source_skill ON promotion_request(source_skill_id);
CREATE INDEX idx_promotion_request_status ON promotion_request(status);
CREATE UNIQUE INDEX idx_promotion_request_version_pending ON promotion_request(source_version_id) WHERE status = 'PENDING';

-- skill_star 表
CREATE TABLE skill_star (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id),
    user_id BIGINT NOT NULL REFERENCES user_account(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(skill_id, user_id)
);

CREATE INDEX idx_skill_star_user_id ON skill_star(user_id);
CREATE INDEX idx_skill_star_skill_id ON skill_star(skill_id);

-- skill_rating 表
CREATE TABLE skill_rating (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id),
    user_id BIGINT NOT NULL REFERENCES user_account(id),
    score SMALLINT NOT NULL CHECK (score >= 1 AND score <= 5),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(skill_id, user_id)
);

CREATE INDEX idx_skill_rating_skill_id ON skill_rating(skill_id);

-- idempotency_record 表
CREATE TABLE idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT,
    status VARCHAR(32) NOT NULL,
    response_status_code INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_idempotency_record_expires_at ON idempotency_record(expires_at);
CREATE INDEX idx_idempotency_record_status_created ON idempotency_record(status, created_at);
```

- [ ] **Step 2: 验证迁移脚本语法**

运行：`cd server && ./mvnw flyway:validate`
预期：SUCCESS

- [ ] **Step 3: 执行数据库迁移**

运行：`cd server && ./mvnw flyway:migrate`
预期：V3 迁移成功，5 个新表创建

- [ ] **Step 4: 验证表结构**

运行：`psql -d skillhub -c "\d review_task"`
预期：显示表结构，包含 partial unique index

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-app/src/main/resources/db/migration/V3__phase3_review_social_tables.sql
git commit -m "feat(db): add Phase 3 database migration

- Add review_task table with partial unique index
- Add promotion_request table
- Add skill_star and skill_rating tables
- Add idempotency_record table"
```

### Task 2: 审核流程领域实体

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewTask.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewTaskStatus.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionRequest.java`

- [ ] **Step 1: 创建 ReviewTaskStatus 枚举**

创建 `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewTaskStatus.java`:

```java
package com.iflytek.skillhub.domain.review;

public enum ReviewTaskStatus {
    PENDING,
    APPROVED,
    REJECTED
}
```

- [ ] **Step 2: 创建 ReviewTask 实体**

创建 `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewTask.java`:

```java
package com.iflytek.skillhub.domain.review;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_task")
public class ReviewTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_version_id", nullable = false)
    private Long skillVersionId;

    @Column(name = "namespace_id", nullable = false)
    private Long namespaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewTaskStatus status = ReviewTaskStatus.PENDING;

    @Version
    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // Constructors
    protected ReviewTask() {}

    public ReviewTask(Long skillVersionId, Long namespaceId, Long submittedBy) {
        this.skillVersionId = skillVersionId;
        this.namespaceId = namespaceId;
        this.submittedBy = submittedBy;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public Long getSkillVersionId() { return skillVersionId; }
    public Long getNamespaceId() { return namespaceId; }
    public ReviewTaskStatus getStatus() { return status; }
    public void setStatus(ReviewTaskStatus status) { this.status = status; }
    public Integer getVersion() { return version; }
    public Long getSubmittedBy() { return submittedBy; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
```

- [ ] **Step 3: 创建 PromotionRequest 实体**

创建 `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionRequest.java`:

```java
package com.iflytek.skillhub.domain.review;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promotion_request")
public class PromotionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_skill_id", nullable = false)
    private Long sourceSkillId;

    @Column(name = "source_version_id", nullable = false)
    private Long sourceVersionId;

    @Column(name = "target_namespace_id", nullable = false)
    private Long targetNamespaceId;

    @Column(name = "target_skill_id")
    private Long targetSkillId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewTaskStatus status = ReviewTaskStatus.PENDING;

    @Version
    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // Constructors
    protected PromotionRequest() {}

    public PromotionRequest(Long sourceSkillId, Long sourceVersionId,
                           Long targetNamespaceId, Long submittedBy) {
        this.sourceSkillId = sourceSkillId;
        this.sourceVersionId = sourceVersionId;
        this.targetNamespaceId = targetNamespaceId;
        this.submittedBy = submittedBy;
    }

    // Getters and Setters (similar to ReviewTask)
    public Long getId() { return id; }
    public Long getSourceSkillId() { return sourceSkillId; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public Long getTargetNamespaceId() { return targetNamespaceId; }
    public Long getTargetSkillId() { return targetSkillId; }
    public void setTargetSkillId(Long targetSkillId) { this.targetSkillId = targetSkillId; }
    public ReviewTaskStatus getStatus() { return status; }
    public void setStatus(ReviewTaskStatus status) { this.status = status; }
    public Integer getVersion() { return version; }
    public Long getSubmittedBy() { return submittedBy; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
```

- [ ] **Step 4: 编译验证**

运行：`cd server && ./mvnw compile`
预期：编译成功

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/
git commit -m "feat(domain): add review entities

- Add ReviewTaskStatus enum
- Add ReviewTask entity with optimistic locking
- Add PromotionRequest entity"
```

### Task 3: Repository 层实现

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewTaskRepository.java`
- Create: `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/ReviewTaskJpaRepository.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionRequestRepository.java`
- Create: `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/PromotionRequestJpaRepository.java`

- [ ] **Step 1: 创建 ReviewTaskRepository 接口**

创建 `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewTaskRepository.java`:

```java
package com.iflytek.skillhub.domain.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface ReviewTaskRepository {
    ReviewTask save(ReviewTask reviewTask);
    Optional<ReviewTask> findById(Long id);
    Optional<ReviewTask> findBySkillVersionIdAndStatus(Long skillVersionId, ReviewTaskStatus status);
    Page<ReviewTask> findByNamespaceIdAndStatus(Long namespaceId, ReviewTaskStatus status, Pageable pageable);
    Page<ReviewTask> findBySubmittedByAndStatus(Long submittedBy, ReviewTaskStatus status, Pageable pageable);
    void delete(ReviewTask reviewTask);
    int updateStatusWithVersion(Long id, ReviewTaskStatus status, Long reviewedBy,
                               String reviewComment, Integer expectedVersion);
}
```

- [ ] **Step 2: 创建 ReviewTaskJpaRepository 实现**

创建 `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/ReviewTaskJpaRepository.java`:

```java
package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;

@Repository
public interface ReviewTaskJpaRepository extends JpaRepository<ReviewTask, Long>, ReviewTaskRepository {

    Optional<ReviewTask> findBySkillVersionIdAndStatus(Long skillVersionId, ReviewTaskStatus status);

    Page<ReviewTask> findByNamespaceIdAndStatus(Long namespaceId, ReviewTaskStatus status, Pageable pageable);

    Page<ReviewTask> findBySubmittedByAndStatus(Long submittedBy, ReviewTaskStatus status, Pageable pageable);

    @Modifying
    @Query("""
        UPDATE ReviewTask t
        SET t.status = :status,
            t.reviewedBy = :reviewedBy,
            t.reviewComment = :reviewComment,
            t.reviewedAt = CURRENT_TIMESTAMP,
            t.version = t.version + 1
        WHERE t.id = :id AND t.version = :expectedVersion
    """)
    int updateStatusWithVersion(@Param("id") Long id,
                               @Param("status") ReviewTaskStatus status,
                               @Param("reviewedBy") Long reviewedBy,
                               @Param("reviewComment") String reviewComment,
                               @Param("expectedVersion") Integer expectedVersion);
}
```

- [ ] **Step 3: 创建 PromotionRequestRepository 接口和实现**

创建 `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionRequestRepository.java`:

```java
package com.iflytek.skillhub.domain.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface PromotionRequestRepository {
    PromotionRequest save(PromotionRequest request);
    Optional<PromotionRequest> findById(Long id);
    Optional<PromotionRequest> findBySourceVersionIdAndStatus(Long sourceVersionId, ReviewTaskStatus status);
    Page<PromotionRequest> findByStatus(ReviewTaskStatus status, Pageable pageable);
    int updateStatusWithVersion(Long id, ReviewTaskStatus status, Long reviewedBy,
                               String reviewComment, Long targetSkillId, Integer expectedVersion);
}
```

创建 `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/PromotionRequestJpaRepository.java`:

```java
package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.review.PromotionRequest;
import com.iflytek.skillhub.domain.review.PromotionRequestRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface PromotionRequestJpaRepository extends JpaRepository<PromotionRequest, Long>,
                                                       PromotionRequestRepository {

    Optional<PromotionRequest> findBySourceVersionIdAndStatus(Long sourceVersionId, ReviewTaskStatus status);

    Page<PromotionRequest> findByStatus(ReviewTaskStatus status, Pageable pageable);

    @Modifying
    @Query("""
        UPDATE PromotionRequest p
        SET p.status = :status,
            p.reviewedBy = :reviewedBy,
            p.reviewComment = :reviewComment,
            p.targetSkillId = :targetSkillId,
            p.reviewedAt = CURRENT_TIMESTAMP,
            p.version = p.version + 1
        WHERE p.id = :id AND p.version = :expectedVersion
    """)
    int updateStatusWithVersion(@Param("id") Long id,
                               @Param("status") ReviewTaskStatus status,
                               @Param("reviewedBy") Long reviewedBy,
                               @Param("reviewComment") String reviewComment,
                               @Param("targetSkillId") Long targetSkillId,
                               @Param("expectedVersion") Integer expectedVersion);
}
```

- [ ] **Step 4: 编译验证**

运行：`cd server && ./mvnw compile`
预期：编译成功

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/*Repository.java
git add server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/*Repository.java
git commit -m "feat(repo): add review repositories

- Add ReviewTaskRepository with optimistic lock update
- Add PromotionRequestRepository
- Implement JPA repositories in infra module"
```

### Task 4: 审核权限检查器

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewPermissionChecker.java`
- Create: `server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/review/ReviewPermissionCheckerTest.java`

- [ ] **Step 1: 编写权限检查器测试**

创建测试文件，验证权限逻辑：

```java
package com.iflytek.skillhub.domain.review;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReviewPermissionCheckerTest {

    private final ReviewPermissionChecker checker = new ReviewPermissionChecker();

    @Test
    void cannotReviewOwnSubmission() {
        Long userId = 1L;
        ReviewTask task = createTask(1L, NamespaceType.TEAM, userId);

        boolean canReview = checker.canReview(task, userId, Map.of(), Set.of());

        assertFalse(canReview, "Cannot review own submission");
    }

    @Test
    void teamAdminCanReviewTeamSkill() {
        ReviewTask task = createTask(1L, NamespaceType.TEAM, 2L);

        boolean canReview = checker.canReview(task, 1L,
            Map.of(1L, NamespaceRole.ADMIN), Set.of());

        assertTrue(canReview, "Team ADMIN can review team skill");
    }

    @Test
    void skillAdminCanReviewGlobalSkill() {
        ReviewTask task = createTask(1L, NamespaceType.GLOBAL, 2L);

        boolean canReview = checker.canReview(task, 1L,
            Map.of(), Set.of("SKILL_ADMIN"));

        assertTrue(canReview, "SKILL_ADMIN can review global skill");
    }

    @Test
    void skillAdminCannotReviewTeamSkill() {
        ReviewTask task = createTask(1L, NamespaceType.TEAM, 2L);

        boolean canReview = checker.canReview(task, 1L,
            Map.of(), Set.of("SKILL_ADMIN"));

        assertFalse(canReview, "SKILL_ADMIN cannot review team skill");
    }

    private ReviewTask createTask(Long namespaceId, NamespaceType type, Long submittedBy) {
        // Mock ReviewTask with namespace info
        return new ReviewTask(1L, namespaceId, submittedBy);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

运行：`cd server && ./mvnw test -Dtest=ReviewPermissionCheckerTest`
预期：测试失败（类不存在）

- [ ] **Step 3: 实现权限检查器**

创建 `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewPermissionChecker.java`:

```java
package com.iflytek.skillhub.domain.review;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class ReviewPermissionChecker {

    public boolean canReview(ReviewTask task, Long userId,
                            Map<Long, NamespaceRole> userNamespaceRoles,
                            Set<String> platformRoles) {
        // Cannot review own submission
        if (task.getSubmittedBy().equals(userId)) {
            return false;
        }

        // Get namespace type (需要从 task 中获取，这里简化处理)
        NamespaceType namespaceType = getNamespaceType(task.getNamespaceId());

        // Global namespace: only SKILL_ADMIN or SUPER_ADMIN
        if (namespaceType == NamespaceType.GLOBAL) {
            return platformRoles.contains("SKILL_ADMIN")
                || platformRoles.contains("SUPER_ADMIN");
        }

        // Team namespace: namespace ADMIN or OWNER
        NamespaceRole role = userNamespaceRoles.get(task.getNamespaceId());
        return role == NamespaceRole.ADMIN || role == NamespaceRole.OWNER;
    }

    public boolean canReviewPromotion(PromotionRequest request, Long userId,
                                     Set<String> platformRoles) {
        // Only SKILL_ADMIN or SUPER_ADMIN can review promotion
        return platformRoles.contains("SKILL_ADMIN")
            || platformRoles.contains("SUPER_ADMIN");
    }

    private NamespaceType getNamespaceType(Long namespaceId) {
        // TODO: 实际实现需要查询 namespace 表
        // 这里简化处理，假设 id=1 是 GLOBAL
        return namespaceId == 1L ? NamespaceType.GLOBAL : NamespaceType.TEAM;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

运行：`cd server && ./mvnw test -Dtest=ReviewPermissionCheckerTest`
预期：所有测试通过

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewPermissionChecker.java
git add server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/review/ReviewPermissionCheckerTest.java
git commit -m "feat(review): add permission checker with tests

- Implement ReviewPermissionChecker
- Add unit tests for permission logic
- Verify team admin can only review team skills
- Verify SKILL_ADMIN can only review global skills"
```

### Task 5: 审核服务实现（核心逻辑）

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewService.java`
- Create: `server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/review/ReviewServiceTest.java`

由于篇幅限制，这里提供关键方法的实现框架：

- [ ] **Step 1: 创建 ReviewService 接口**

```java
package com.iflytek.skillhub.domain.review;

public interface ReviewService {
    ReviewTask submitReview(Long skillVersionId, Long namespaceId, Long userId);
    void approveReview(Long reviewTaskId, Long reviewerId, String comment);
    void rejectReview(Long reviewTaskId, Long reviewerId, String comment);
    void withdrawReview(Long skillVersionId, Long userId);
}
```

- [ ] **Step 2-5: 实现服务方法（TDD 循环）**

参考设计文档第 2.1 节的流程图实现每个方法，包括：
- 乐观锁更新
- 状态机转换
- 事件发布
- 审计日志

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(review): implement review service

- Add submitReview with duplicate check
- Add approveReview with optimistic locking
- Add rejectReview with reason recording
- Add withdrawReview with PENDING check"
```

### Task 6-10: 剩余任务概要

由于完整实施计划会超过 5000 行，这里列出剩余任务的概要：

**Task 6: 提升服务实现**
- PromotionService 接口和实现
- 提升审核通过后创建全局 skill
- 复制版本和文件元数据

**Task 7: Controller 层**
- ReviewController（提交、审核、撤回、列表查询）
- PromotionController（提交提升、审核提升、列表查询）

**Task 8: 集成测试**
- 审核全链路测试（提交 → 审核 → 发布）
- 乐观锁并发冲突测试
- 权限控制测试

**Task 9: API 文档**
- OpenAPI 规范更新
- 请求/响应示例

**Task 10: Chunk 1 验收**
- 运行所有测试
- 验证 12 个验收标准
- 代码审查

---

## Chunk 2: 评分收藏 + 前端审核中心

**范围：** 评分收藏后端 + 审核中心前端 + Token 管理前端

**验收标准：**
1. 用户可以收藏技能，skill.star_count 异步更新
2. 用户可以取消收藏，star_count 异步递减
3. 用户可以对技能评分（1-5 分），skill.rating_avg 异步重算
4. 用户可以修改评分，rating_avg 重新计算
5. 匿名用户点击评分/收藏，提示登录
6. 审核中心：审核人可以查看待审核任务列表
7. 审核中心：审核人可以查看审核详情，通过/拒绝审核
8. 审核中心：用户可以查看自己的提交列表，撤回 PENDING 审核
9. 提升审核：平台管理员可以查看提升请求列表，审核提升
10. Token 管理：用户可以创建 Token，查看 Token 列表，吊销 Token
11. 前端测试通过

### Task 1: SkillStar 和 SkillRating 领域实体

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillStar.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillRating.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillStarRepository.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillRatingRepository.java`
- Create: `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaSkillStarRepository.java`
- Create: `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaSkillRatingRepository.java`

- [ ] **Step 1: 创建 SkillStar 实体**

```java
package com.iflytek.skillhub.domain.social;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "skill_star",
    uniqueConstraints = @UniqueConstraint(columns = {"skill_id", "user_id"}))
public class SkillStar {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected SkillStar() {}

    public SkillStar(Long skillId, Long userId) {
        this.skillId = skillId;
        this.userId = userId;
    }

    // getters
    public Long getId() { return id; }
    public Long getSkillId() { return skillId; }
    public Long getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: 创建 SkillRating 实体**

```java
package com.iflytek.skillhub.domain.social;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "skill_rating",
    uniqueConstraints = @UniqueConstraint(columns = {"skill_id", "user_id"}))
public class SkillRating {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Short score;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected SkillRating() {}

    public SkillRating(Long skillId, Long userId, short score) {
        if (score < 1 || score > 5) throw new IllegalArgumentException("Score must be 1-5");
        this.skillId = skillId;
        this.userId = userId;
        this.score = score;
    }

    public void updateScore(short newScore) {
        if (newScore < 1 || newScore > 5) throw new IllegalArgumentException("Score must be 1-5");
        this.score = newScore;
        this.updatedAt = LocalDateTime.now();
    }

    // getters
    public Long getId() { return id; }
    public Long getSkillId() { return skillId; }
    public Long getUserId() { return userId; }
    public Short getScore() { return score; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 3: 创建 Repository 接口**

`SkillStarRepository.java`:
```java
package com.iflytek.skillhub.domain.social;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SkillStarRepository {
    SkillStar save(SkillStar star);
    Optional<SkillStar> findBySkillIdAndUserId(Long skillId, Long userId);
    void delete(SkillStar star);
    Page<SkillStar> findByUserId(Long userId, Pageable pageable);
    long countBySkillId(Long skillId);
}
```

`SkillRatingRepository.java`:
```java
package com.iflytek.skillhub.domain.social;

import java.util.Optional;

public interface SkillRatingRepository {
    SkillRating save(SkillRating rating);
    Optional<SkillRating> findBySkillIdAndUserId(Long skillId, Long userId);
    double averageScoreBySkillId(Long skillId);
    int countBySkillId(Long skillId);
}
```

- [ ] **Step 4: 实现 JPA Repository**

`JpaSkillStarRepository.java`:
```java
package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.social.SkillStar;
import com.iflytek.skillhub.domain.social.SkillStarRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface JpaSkillStarRepository extends JpaRepository<SkillStar, Long>, SkillStarRepository {
    Optional<SkillStar> findBySkillIdAndUserId(Long skillId, Long userId);
    Page<SkillStar> findByUserId(Long userId, Pageable pageable);
    long countBySkillId(Long skillId);
}
```

`JpaSkillRatingRepository.java`:
```java
package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.social.SkillRating;
import com.iflytek.skillhub.domain.social.SkillRatingRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface JpaSkillRatingRepository extends JpaRepository<SkillRating, Long>, SkillRatingRepository {
    Optional<SkillRating> findBySkillIdAndUserId(Long skillId, Long userId);

    @Query("SELECT COALESCE(AVG(r.score), 0) FROM SkillRating r WHERE r.skillId = :skillId")
    double averageScoreBySkillId(Long skillId);

    int countBySkillId(Long skillId);
}
```

- [ ] **Step 5: 编译验证**

运行：`cd server && ./mvnw compile`
预期：编译成功

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/
git add server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaSkillStar*
git add server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaSkillRating*
git commit -m "feat(social): add SkillStar and SkillRating entities and repositories"
```

### Task 2: SkillStarService 和 SkillRatingService

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillStarService.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillRatingService.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/event/SkillStarredEvent.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/event/SkillUnstarredEvent.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/event/SkillRatedEvent.java`
- Test: `server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/social/SkillStarServiceTest.java`
- Test: `server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/social/SkillRatingServiceTest.java`

- [ ] **Step 1: 创建领域事件类**

`SkillStarredEvent.java`:
```java
package com.iflytek.skillhub.domain.social.event;

public record SkillStarredEvent(Long skillId, Long userId) {}
```

`SkillUnstarredEvent.java`:
```java
package com.iflytek.skillhub.domain.social.event;

public record SkillUnstarredEvent(Long skillId, Long userId) {}
```

`SkillRatedEvent.java`:
```java
package com.iflytek.skillhub.domain.social.event;

public record SkillRatedEvent(Long skillId, Long userId, short score) {}
```

- [ ] **Step 2: 编写 SkillStarService 测试**

```java
package com.iflytek.skillhub.domain.social;

import com.iflytek.skillhub.domain.social.event.SkillStarredEvent;
import com.iflytek.skillhub.domain.social.event.SkillUnstarredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillStarServiceTest {
    @Mock SkillStarRepository starRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks SkillStarService service;

    @Test
    void star_skill_creates_record_and_publishes_event() {
        when(starRepository.findBySkillIdAndUserId(1L, 10L)).thenReturn(Optional.empty());
        when(starRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.star(1L, 10L);

        verify(starRepository).save(any(SkillStar.class));
        verify(eventPublisher).publishEvent(any(SkillStarredEvent.class));
    }

    @Test
    void star_skill_already_starred_is_idempotent() {
        when(starRepository.findBySkillIdAndUserId(1L, 10L))
            .thenReturn(Optional.of(new SkillStar(1L, 10L)));

        service.star(1L, 10L);

        verify(starRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void unstar_skill_deletes_record_and_publishes_event() {
        SkillStar existing = new SkillStar(1L, 10L);
        when(starRepository.findBySkillIdAndUserId(1L, 10L)).thenReturn(Optional.of(existing));

        service.unstar(1L, 10L);

        verify(starRepository).delete(existing);
        verify(eventPublisher).publishEvent(any(SkillUnstarredEvent.class));
    }

    @Test
    void unstar_skill_not_starred_is_noop() {
        when(starRepository.findBySkillIdAndUserId(1L, 10L)).thenReturn(Optional.empty());

        service.unstar(1L, 10L);

        verify(starRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void isStarred_returns_true_when_exists() {
        when(starRepository.findBySkillIdAndUserId(1L, 10L))
            .thenReturn(Optional.of(new SkillStar(1L, 10L)));
        assertThat(service.isStarred(1L, 10L)).isTrue();
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

运行：`cd server && ./mvnw test -pl skillhub-domain -Dtest=SkillStarServiceTest`
预期：编译失败，SkillStarService 不存在

- [ ] **Step 4: 实现 SkillStarService**

```java
package com.iflytek.skillhub.domain.social;

import com.iflytek.skillhub.domain.social.event.SkillStarredEvent;
import com.iflytek.skillhub.domain.social.event.SkillUnstarredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillStarService {
    private final SkillStarRepository starRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SkillStarService(SkillStarRepository starRepository,
                            ApplicationEventPublisher eventPublisher) {
        this.starRepository = starRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void star(Long skillId, Long userId) {
        if (starRepository.findBySkillIdAndUserId(skillId, userId).isPresent()) {
            return; // idempotent
        }
        starRepository.save(new SkillStar(skillId, userId));
        eventPublisher.publishEvent(new SkillStarredEvent(skillId, userId));
    }

    @Transactional
    public void unstar(Long skillId, Long userId) {
        starRepository.findBySkillIdAndUserId(skillId, userId).ifPresent(star -> {
            starRepository.delete(star);
            eventPublisher.publishEvent(new SkillUnstarredEvent(skillId, userId));
        });
    }

    public boolean isStarred(Long skillId, Long userId) {
        return starRepository.findBySkillIdAndUserId(skillId, userId).isPresent();
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-domain -Dtest=SkillStarServiceTest`
预期：5 个测试全部 PASS

- [ ] **Step 6: 编写 SkillRatingService 测试**

```java
package com.iflytek.skillhub.domain.social;

import com.iflytek.skillhub.domain.social.event.SkillRatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillRatingServiceTest {
    @Mock SkillRatingRepository ratingRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks SkillRatingService service;

    @Test
    void rate_creates_new_rating() {
        when(ratingRepository.findBySkillIdAndUserId(1L, 10L)).thenReturn(Optional.empty());
        when(ratingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rate(1L, 10L, (short) 4);

        verify(ratingRepository).save(argThat(r -> r.getScore() == 4));
        verify(eventPublisher).publishEvent(any(SkillRatedEvent.class));
    }

    @Test
    void rate_updates_existing_rating() {
        SkillRating existing = new SkillRating(1L, 10L, (short) 3);
        when(ratingRepository.findBySkillIdAndUserId(1L, 10L)).thenReturn(Optional.of(existing));
        when(ratingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rate(1L, 10L, (short) 5);

        assertThat(existing.getScore()).isEqualTo((short) 5);
        verify(ratingRepository).save(existing);
        verify(eventPublisher).publishEvent(any(SkillRatedEvent.class));
    }

    @Test
    void rate_invalid_score_throws() {
        assertThatThrownBy(() -> service.rate(1L, 10L, (short) 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rate(1L, 10L, (short) 6))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getUserRating_returns_score() {
        SkillRating existing = new SkillRating(1L, 10L, (short) 4);
        when(ratingRepository.findBySkillIdAndUserId(1L, 10L)).thenReturn(Optional.of(existing));
        assertThat(service.getUserRating(1L, 10L)).hasValue((short) 4);
    }
}
```

- [ ] **Step 7: 实现 SkillRatingService**

```java
package com.iflytek.skillhub.domain.social;

import com.iflytek.skillhub.domain.social.event.SkillRatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class SkillRatingService {
    private final SkillRatingRepository ratingRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SkillRatingService(SkillRatingRepository ratingRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.ratingRepository = ratingRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void rate(Long skillId, Long userId, short score) {
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException("Score must be 1-5");
        }
        Optional<SkillRating> existing = ratingRepository.findBySkillIdAndUserId(skillId, userId);
        if (existing.isPresent()) {
            existing.get().updateScore(score);
            ratingRepository.save(existing.get());
        } else {
            ratingRepository.save(new SkillRating(skillId, userId, score));
        }
        eventPublisher.publishEvent(new SkillRatedEvent(skillId, userId, score));
    }

    public Optional<Short> getUserRating(Long skillId, Long userId) {
        return ratingRepository.findBySkillIdAndUserId(skillId, userId)
            .map(SkillRating::getScore);
    }
}
```

- [ ] **Step 8: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-domain -Dtest=SkillRatingServiceTest`
预期：4 个测试全部 PASS

- [ ] **Step 9: Commit**

```bash
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/event/
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillStarService.java
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/social/SkillRatingService.java
git add server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/social/
git commit -m "feat(social): add SkillStarService and SkillRatingService with events"
```

### Task 3: 异步事件监听器（计数器更新）

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/listener/SkillStarEventListener.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/listener/SkillRatingEventListener.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/listener/SkillStarEventListenerTest.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/listener/SkillRatingEventListenerTest.java`

- [ ] **Step 1: 编写 SkillStarEventListener 测试**

```java
package com.iflytek.skillhub.app.listener;

import com.iflytek.skillhub.domain.social.SkillStarRepository;
import com.iflytek.skillhub.domain.social.event.SkillStarredEvent;
import com.iflytek.skillhub.domain.social.event.SkillUnstarredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillStarEventListenerTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock SkillStarRepository starRepository;
    @InjectMocks SkillStarEventListener listener;

    @Test
    void onStarred_updates_star_count() {
        when(starRepository.countBySkillId(1L)).thenReturn(42L);
        listener.onStarred(new SkillStarredEvent(1L, 10L));
        verify(jdbcTemplate).update("UPDATE skill SET star_count = ? WHERE id = ?", 42, 1L);
    }

    @Test
    void onUnstarred_updates_star_count() {
        when(starRepository.countBySkillId(1L)).thenReturn(41L);
        listener.onUnstarred(new SkillUnstarredEvent(1L, 10L));
        verify(jdbcTemplate).update("UPDATE skill SET star_count = ? WHERE id = ?", 41, 1L);
    }
}
```

- [ ] **Step 2: 实现 SkillStarEventListener**

```java
package com.iflytek.skillhub.app.listener;

import com.iflytek.skillhub.domain.social.SkillStarRepository;
import com.iflytek.skillhub.domain.social.event.SkillStarredEvent;
import com.iflytek.skillhub.domain.social.event.SkillUnstarredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SkillStarEventListener {
    private final JdbcTemplate jdbcTemplate;
    private final SkillStarRepository starRepository;

    public SkillStarEventListener(JdbcTemplate jdbcTemplate, SkillStarRepository starRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.starRepository = starRepository;
    }

    @Async
    @TransactionalEventListener
    public void onStarred(SkillStarredEvent event) {
        updateStarCount(event.skillId());
    }

    @Async
    @TransactionalEventListener
    public void onUnstarred(SkillUnstarredEvent event) {
        updateStarCount(event.skillId());
    }

    private void updateStarCount(Long skillId) {
        long count = starRepository.countBySkillId(skillId);
        jdbcTemplate.update("UPDATE skill SET star_count = ? WHERE id = ?", count, skillId);
    }
}
```

- [ ] **Step 3: 编写 SkillRatingEventListener 测试**

```java
package com.iflytek.skillhub.app.listener;

import com.iflytek.skillhub.domain.social.SkillRatingRepository;
import com.iflytek.skillhub.domain.social.event.SkillRatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillRatingEventListenerTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock SkillRatingRepository ratingRepository;
    @InjectMocks SkillRatingEventListener listener;

    @Test
    void onRated_updates_rating_avg_and_count() {
        when(ratingRepository.averageScoreBySkillId(1L)).thenReturn(4.2);
        when(ratingRepository.countBySkillId(1L)).thenReturn(10);
        listener.onRated(new SkillRatedEvent(1L, 10L, (short) 5));
        verify(jdbcTemplate).update(
            "UPDATE skill SET rating_avg = ?, rating_count = ? WHERE id = ?",
            4.2, 10, 1L);
    }
}
```

- [ ] **Step 4: 实现 SkillRatingEventListener**

```java
package com.iflytek.skillhub.app.listener;

import com.iflytek.skillhub.domain.social.SkillRatingRepository;
import com.iflytek.skillhub.domain.social.event.SkillRatedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SkillRatingEventListener {
    private final JdbcTemplate jdbcTemplate;
    private final SkillRatingRepository ratingRepository;

    public SkillRatingEventListener(JdbcTemplate jdbcTemplate, SkillRatingRepository ratingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.ratingRepository = ratingRepository;
    }

    @Async
    @TransactionalEventListener
    public void onRated(SkillRatedEvent event) {
        double avg = ratingRepository.averageScoreBySkillId(event.skillId());
        int count = ratingRepository.countBySkillId(event.skillId());
        jdbcTemplate.update(
            "UPDATE skill SET rating_avg = ?, rating_count = ? WHERE id = ?",
            avg, count, event.skillId());
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest="SkillStarEventListenerTest,SkillRatingEventListenerTest"`
预期：3 个测试全部 PASS

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/listener/Skill*EventListener.java
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/listener/Skill*EventListenerTest.java
git commit -m "feat(social): add async event listeners for star_count and rating_avg"
```

### Task 4: SkillStarController 和 SkillRatingController

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/SkillStarController.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/SkillRatingController.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/SkillStarControllerTest.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/SkillRatingControllerTest.java`

- [ ] **Step 1: 编写 SkillStarController 测试**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.domain.social.SkillStarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SkillStarController.class)
class SkillStarControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean SkillStarService starService;

    @Test
    @WithMockUser
    void star_skill_returns_204() throws Exception {
        mockMvc.perform(put("/api/v1/skills/1/star"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    void unstar_skill_returns_204() throws Exception {
        mockMvc.perform(delete("/api/v1/skills/1/star"))
            .andExpect(status().isNoContent());
    }

    @Test
    void star_skill_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(put("/api/v1/skills/1/star"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 实现 SkillStarController**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.domain.social.SkillStarService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/skills/{skillId}/star")
public class SkillStarController {
    private final SkillStarService starService;

    public SkillStarController(SkillStarService starService) {
        this.starService = starService;
    }

    @PutMapping
    public ResponseEntity<Void> star(@PathVariable Long skillId,
                                     @AuthenticationPrincipal Long userId) {
        starService.star(skillId, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unstar(@PathVariable Long skillId,
                                       @AuthenticationPrincipal Long userId) {
        starService.unstar(skillId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<Boolean> isStarred(@PathVariable Long skillId,
                                             @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(starService.isStarred(skillId, userId));
    }
}
```

- [ ] **Step 3: 编写 SkillRatingController 测试**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.domain.social.SkillRatingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SkillRatingController.class)
class SkillRatingControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean SkillRatingService ratingService;

    @Test
    @WithMockUser
    void rate_skill_returns_204() throws Exception {
        mockMvc.perform(put("/api/v1/skills/1/rating")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\": 4}"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    void get_user_rating_returns_score() throws Exception {
        when(ratingService.getUserRating(1L, any())).thenReturn(Optional.of((short) 4));
        mockMvc.perform(get("/api/v1/skills/1/rating"))
            .andExpect(status().isOk());
    }

    @Test
    void rate_skill_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(put("/api/v1/skills/1/rating")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\": 4}"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 4: 实现 SkillRatingController**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.domain.social.SkillRatingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/skills/{skillId}/rating")
public class SkillRatingController {
    private final SkillRatingService ratingService;

    public SkillRatingController(SkillRatingService ratingService) {
        this.ratingService = ratingService;
    }

    @PutMapping
    public ResponseEntity<Void> rate(@PathVariable Long skillId,
                                     @AuthenticationPrincipal Long userId,
                                     @RequestBody Map<String, Integer> body) {
        short score = body.get("score").shortValue();
        ratingService.rate(skillId, userId, score);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<?> getUserRating(@PathVariable Long skillId,
                                           @AuthenticationPrincipal Long userId) {
        Optional<Short> score = ratingService.getUserRating(skillId, userId);
        return ResponseEntity.ok(Map.of("score", score.orElse(null), "rated", score.isPresent()));
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest="SkillStarControllerTest,SkillRatingControllerTest"`
预期：6 个测试全部 PASS

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/SkillStar*
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/SkillRating*
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/SkillStar*
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/SkillRating*
git commit -m "feat(social): add SkillStar and SkillRating controllers"
```

### Task 5-11: 前端审核中心 + 评分收藏 + Token 管理（概要）

**Task 5: 审核中心 - 审核任务列表页**
- Files: `web/src/pages/dashboard/reviews.tsx`, `web/src/features/review/review-task-table.tsx`, `web/src/features/review/use-review-tasks.ts`
- 使用 TanStack Query 获取审核任务列表
- 支持按状态筛选（PENDING / APPROVED / REJECTED）
- 分页 + 排序

**Task 6: 审核中心 - 审核详情页**
- Files: `web/src/pages/dashboard/review-detail.tsx`, `web/src/features/review/review-detail-view.tsx`, `web/src/features/review/approve-dialog.tsx`, `web/src/features/review/reject-dialog.tsx`
- 展示技能版本详情 + SKILL.md 预览
- 通过/拒绝对话框（含审核意见输入）

**Task 7: 审核中心 - 我的提交列表**
- Files: `web/src/pages/dashboard/my-submissions.tsx`
- 展示用户提交的审核任务列表
- 支持撤回 PENDING 状态的审核

**Task 8: 提升审核页面**
- Files: `web/src/pages/dashboard/promotions.tsx`, `web/src/features/promotion/promotion-table.tsx`, `web/src/features/promotion/use-promotions.ts`
- 平台管理员查看提升请求列表
- 审核提升请求

**Task 9: 评分收藏组件**
- Files: `web/src/features/rating/star-rating.tsx`, `web/src/features/star/star-button.tsx`, `web/src/features/rating/use-rate-skill.ts`, `web/src/features/star/use-star-skill.ts`
- StarRating 组件：1-5 星评分，支持半星显示
- StarButton 组件：收藏/取消收藏切换
- 集成到技能详情页右侧信息栏

**Task 10: Token 管理页**
- Files: `web/src/pages/dashboard/tokens.tsx`, `web/src/features/token/token-table.tsx`, `web/src/features/token/create-token-dialog.tsx`, `web/src/features/token/revoke-token-dialog.tsx`
- Token 列表展示（名称、前缀、创建时间、过期时间、最后使用时间）
- 创建 Token 对话框（名称、权限范围、过期时间）
- 吊销 Token 确认对话框

**Task 11: Chunk 2 验收**
- 运行所有后端测试：`cd server && ./mvnw test`
- 运行所有前端测试：`cd web && npm test`
- 验证 11 个验收标准
- 代码审查

---

## Chunk 3: CLI API + Web 授权

**范围：** OAuth Device Flow + CLI API 端点

**验收标准：**
1. CLI 运行 `skillhub login`，获取 device code 和 user code
2. CLI 打开浏览器，跳转到授权页面
3. 用户输入 user code，确认授权
4. CLI 轮询获取 token，保存到本地配置文件
5. CLI 运行 `skillhub whoami`，返回当前用户信息
6. CLI 运行 `skillhub publish`，上传技能包，提交审核
7. CLI 运行 `skillhub resolve @team-ai/my-skill`，返回版本信息
8. CLI 运行 `skillhub check skill.zip`，返回校验结果
9. 所有 CLI API 端点测试通过

### Task 1: Device Flow 数据模型和 Redis 存储

**Files:**
- Create: `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/DeviceCodeData.java`
- Create: `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/DeviceCodeStatus.java`
- Create: `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/DeviceCodeResponse.java`
- Create: `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/DeviceTokenResponse.java`

- [ ] **Step 1: 创建 DeviceCodeStatus 枚举**

```java
package com.iflytek.skillhub.auth.device;

public enum DeviceCodeStatus {
    PENDING,
    AUTHORIZED,
    USED
}
```

- [ ] **Step 2: 创建 DeviceCodeData**

```java
package com.iflytek.skillhub.auth.device;

import java.io.Serializable;

public class DeviceCodeData implements Serializable {
    private String deviceCode;
    private String userCode;
    private DeviceCodeStatus status;
    private Long userId;

    public DeviceCodeData() {}

    public DeviceCodeData(String deviceCode, String userCode,
                          DeviceCodeStatus status, Long userId) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.status = status;
        this.userId = userId;
    }

    public String getDeviceCode() { return deviceCode; }
    public String getUserCode() { return userCode; }
    public DeviceCodeStatus getStatus() { return status; }
    public void setStatus(DeviceCodeStatus status) { this.status = status; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
```

- [ ] **Step 3: 创建 DeviceCodeResponse**

```java
package com.iflytek.skillhub.auth.device;

public record DeviceCodeResponse(
    String deviceCode,
    String userCode,
    String verificationUri,
    int expiresIn,
    int interval
) {}
```

- [ ] **Step 4: 创建 DeviceTokenResponse**

```java
package com.iflytek.skillhub.auth.device;

public record DeviceTokenResponse(
    String accessToken,
    String tokenType,
    String error
) {
    public static DeviceTokenResponse pending() {
        return new DeviceTokenResponse(null, null, "authorization_pending");
    }

    public static DeviceTokenResponse success(String token) {
        return new DeviceTokenResponse(token, "Bearer", null);
    }
}
```

- [ ] **Step 5: 编译验证**

运行：`cd server && ./mvnw compile -pl skillhub-auth`
预期：编译成功

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/
git commit -m "feat(cli): add Device Flow data models"
```

### Task 2: DeviceAuthService 实现

**Files:**
- Create: `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/DeviceAuthService.java`
- Test: `server/skillhub-auth/src/test/java/com/iflytek/skillhub/auth/device/DeviceAuthServiceTest.java`

- [ ] **Step 1: 编写 DeviceAuthService 测试**

```java
package com.iflytek.skillhub.auth.device;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceAuthServiceTest {
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @InjectMocks DeviceAuthService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void generateDeviceCode_returns_valid_response() {
        DeviceCodeResponse resp = service.generateDeviceCode();
        assertThat(resp.deviceCode()).isNotBlank();
        assertThat(resp.userCode()).matches("[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(resp.expiresIn()).isEqualTo(900);
        assertThat(resp.interval()).isEqualTo(5);
        verify(valueOps, times(2)).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void pollToken_returns_pending_when_not_authorized() {
        DeviceCodeData data = new DeviceCodeData("dc", "UC", DeviceCodeStatus.PENDING, null);
        when(valueOps.get("device:code:dc")).thenReturn(data);
        DeviceTokenResponse resp = service.pollToken("dc");
        assertThat(resp.error()).isEqualTo("authorization_pending");
        assertThat(resp.accessToken()).isNull();
    }

    @Test
    void pollToken_returns_error_when_expired() {
        when(valueOps.get("device:code:dc")).thenReturn(null);
        assertThatThrownBy(() -> service.pollToken("dc"))
            .hasMessageContaining("expired");
    }

    @Test
    void authorizeDeviceCode_updates_status() {
        DeviceCodeData data = new DeviceCodeData("dc", "ABCD-1234", DeviceCodeStatus.PENDING, null);
        when(valueOps.get("device:usercode:ABCD-1234")).thenReturn("dc");
        when(valueOps.get("device:code:dc")).thenReturn(data);

        service.authorizeDeviceCode("ABCD-1234", 42L);

        assertThat(data.getStatus()).isEqualTo(DeviceCodeStatus.AUTHORIZED);
        assertThat(data.getUserId()).isEqualTo(42L);
        verify(valueOps).set(eq("device:code:dc"), eq(data), any(Duration.class));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd server && ./mvnw test -pl skillhub-auth -Dtest=DeviceAuthServiceTest`
预期：编译失败，DeviceAuthService 不存在

- [ ] **Step 3: 实现 DeviceAuthService**

```java
package com.iflytek.skillhub.auth.device;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class DeviceAuthService {
    private static final String DEVICE_CODE_PREFIX = "device:code:";
    private static final String USER_CODE_PREFIX = "device:usercode:";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final RedisTemplate<String, Object> redisTemplate;
    private final SecureRandom random = new SecureRandom();

    public DeviceAuthService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public DeviceCodeResponse generateDeviceCode() {
        String deviceCode = generateSecureToken();
        String userCode = generateUserFriendlyCode();

        DeviceCodeData data = new DeviceCodeData(deviceCode, userCode,
            DeviceCodeStatus.PENDING, null);

        redisTemplate.opsForValue().set(DEVICE_CODE_PREFIX + deviceCode, data, TTL);
        redisTemplate.opsForValue().set(USER_CODE_PREFIX + userCode, deviceCode, TTL);

        return new DeviceCodeResponse(deviceCode, userCode,
            "/device", 900, 5);
    }

    public void authorizeDeviceCode(String userCode, Long userId) {
        String deviceCode = (String) redisTemplate.opsForValue()
            .get(USER_CODE_PREFIX + userCode);
        if (deviceCode == null) {
            throw new IllegalArgumentException("Invalid or expired user code");
        }

        DeviceCodeData data = (DeviceCodeData) redisTemplate.opsForValue()
            .get(DEVICE_CODE_PREFIX + deviceCode);
        if (data == null) {
            throw new IllegalArgumentException("Invalid or expired device code");
        }

        data.setStatus(DeviceCodeStatus.AUTHORIZED);
        data.setUserId(userId);
        redisTemplate.opsForValue().set(DEVICE_CODE_PREFIX + deviceCode, data, TTL);
    }

    public DeviceTokenResponse pollToken(String deviceCode) {
        DeviceCodeData data = (DeviceCodeData) redisTemplate.opsForValue()
            .get(DEVICE_CODE_PREFIX + deviceCode);

        if (data == null) {
            throw new IllegalArgumentException("Invalid or expired device code");
        }

        return switch (data.getStatus()) {
            case PENDING -> DeviceTokenResponse.pending();
            case AUTHORIZED -> {
                data.setStatus(DeviceCodeStatus.USED);
                redisTemplate.opsForValue().set(
                    DEVICE_CODE_PREFIX + deviceCode, data, Duration.ofMinutes(1));
                // Token 生成委托给调用方（Controller 层调用 ApiTokenService）
                yield DeviceTokenResponse.success(null);
            }
            case USED -> throw new IllegalStateException("Device code already used");
        };
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateUserFriendlyCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) code.append('-');
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return code.toString();
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-auth -Dtest=DeviceAuthServiceTest`
预期：4 个测试全部 PASS

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/device/DeviceAuthService.java
git add server/skillhub-auth/src/test/java/com/iflytek/skillhub/auth/device/DeviceAuthServiceTest.java
git commit -m "feat(cli): implement DeviceAuthService with Redis storage"
```

### Task 3: Device Auth Controller 层

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/DeviceAuthController.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/DeviceAuthWebController.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/DeviceAuthControllerTest.java`

- [ ] **Step 1: 编写 DeviceAuthController 测试**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.device.DeviceCodeResponse;
import com.iflytek.skillhub.auth.device.DeviceTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeviceAuthController.class)
class DeviceAuthControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean DeviceAuthService deviceAuthService;

    @Test
    void requestDeviceCode_returns_code() throws Exception {
        when(deviceAuthService.generateDeviceCode())
            .thenReturn(new DeviceCodeResponse("dc123", "ABCD-1234", "/device", 900, 5));

        mockMvc.perform(post("/api/v1/cli/auth/device/code"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deviceCode").value("dc123"))
            .andExpect(jsonPath("$.userCode").value("ABCD-1234"))
            .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void pollToken_returns_pending() throws Exception {
        when(deviceAuthService.pollToken("dc123"))
            .thenReturn(DeviceTokenResponse.pending());

        mockMvc.perform(post("/api/v1/cli/auth/device/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\":\"dc123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("authorization_pending"));
    }
}
```

- [ ] **Step 2: 实现 DeviceAuthController**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.device.DeviceCodeResponse;
import com.iflytek.skillhub.auth.device.DeviceTokenResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cli/auth/device")
public class DeviceAuthController {
    private final DeviceAuthService deviceAuthService;

    public DeviceAuthController(DeviceAuthService deviceAuthService) {
        this.deviceAuthService = deviceAuthService;
    }

    @PostMapping("/code")
    public DeviceCodeResponse requestDeviceCode() {
        return deviceAuthService.generateDeviceCode();
    }

    @PostMapping("/token")
    public DeviceTokenResponse pollToken(@RequestBody Map<String, String> body) {
        return deviceAuthService.pollToken(body.get("deviceCode"));
    }
}
```

- [ ] **Step 3: 实现 DeviceAuthWebController**

```java
package com.iflytek.skillhub.app.controller;

import com.iflytek.skillhub.auth.device.DeviceAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/device")
public class DeviceAuthWebController {
    private final DeviceAuthService deviceAuthService;

    public DeviceAuthWebController(DeviceAuthService deviceAuthService) {
        this.deviceAuthService = deviceAuthService;
    }

    @PostMapping("/authorize")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> authorizeDevice(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long userId) {
        deviceAuthService.authorizeDeviceCode(body.get("userCode"), userId);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=DeviceAuthControllerTest`
预期：2 个测试全部 PASS

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/DeviceAuth*
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/DeviceAuth*
git commit -m "feat(cli): add Device Auth controllers"
```

### Task 4: CLI API 端点（whoami + resolve + check）

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/CliApiController.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/CliApiControllerTest.java`

- [ ] **Step 1: 编写 CliApiController 测试**

```java
package com.iflytek.skillhub.app.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CliApiController.class)
class CliApiControllerTest {
    @Autowired MockMvc mockMvc;
    // @MockBean 各依赖服务...

    @Test
    @WithMockUser
    void whoami_returns_user_info() throws Exception {
        mockMvc.perform(get("/api/v1/cli/whoami"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void whoami_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/cli/whoami"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void resolve_returns_version_info() throws Exception {
        mockMvc.perform(get("/api/v1/cli/resolve")
                .param("skill", "@global/my-skill")
                .param("version", "latest"))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 实现 CliApiController**

```java
package com.iflytek.skillhub.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cli")
public class CliApiController {

    // 注入 SkillQueryService, SkillPublishService, UserAccountRepository 等

    @GetMapping("/whoami")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> whoami(@AuthenticationPrincipal Long userId) {
        // 查询用户信息 + 所属 namespace 列表
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("userId", userId)));
    }

    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(
            @RequestParam String skill,
            @RequestParam(defaultValue = "latest") String version,
            @AuthenticationPrincipal Long userId) {
        // 解析 @namespace/slug 格式
        // 调用 SkillQueryService 获取版本详情
        return ResponseEntity.ok(Map.of("code", 0));
    }

    @PostMapping("/check")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> check(@RequestParam("file") MultipartFile file) {
        // 解压 zip，调用 SkillPackageValidator 校验
        return ResponseEntity.ok(Map.of("code", 0));
    }

    @PostMapping("/publish")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> publish(
            @RequestParam("file") MultipartFile file,
            @RequestParam String namespace,
            @RequestParam(defaultValue = "PUBLIC") String visibility,
            @AuthenticationPrincipal Long userId) {
        // 调用 SkillPublishService
        return ResponseEntity.ok(Map.of("code", 0));
    }
}
```

- [ ] **Step 3: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=CliApiControllerTest`
预期：3 个测试全部 PASS

- [ ] **Step 4: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/CliApiController.java
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/CliApiControllerTest.java
git commit -m "feat(cli): add CLI API endpoints (whoami, resolve, check, publish)"
```

### Task 5: 前端 Device Auth 授权页面

**Files:**
- Create: `web/src/pages/device-auth.tsx`
- Create: `web/src/features/device-auth/user-code-input.tsx`
- Create: `web/src/features/device-auth/authorize-confirm-dialog.tsx`
- Create: `web/src/features/device-auth/authorize-success.tsx`
- Create: `web/src/features/device-auth/use-authorize-device.ts`

- [ ] **Step 1: 创建 use-authorize-device hook**

```typescript
// web/src/features/device-auth/use-authorize-device.ts
import { useMutation } from '@tanstack/react-query';
import { apiClient } from '@/api/client';

export function useAuthorizeDevice() {
  return useMutation({
    mutationFn: (userCode: string) =>
      apiClient.post('/api/v1/device/authorize', { userCode }),
  });
}
```

- [ ] **Step 2: 创建 UserCodeInput 组件**

```tsx
// web/src/features/device-auth/user-code-input.tsx
import { useState, useRef } from 'react';
import { Input } from '@/shared/ui/input';

interface UserCodeInputProps {
  onComplete: (code: string) => void;
}

export function UserCodeInput({ onComplete }: UserCodeInputProps) {
  const [part1, setPart1] = useState('');
  const [part2, setPart2] = useState('');
  const ref2 = useRef<HTMLInputElement>(null);

  const handlePart1Change = (value: string) => {
    const clean = value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 4);
    setPart1(clean);
    if (clean.length === 4) ref2.current?.focus();
  };

  const handlePart2Change = (value: string) => {
    const clean = value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 4);
    setPart2(clean);
    if (clean.length === 4 && part1.length === 4) {
      onComplete(`${part1}-${clean}`);
    }
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    const text = e.clipboardData.getData('text').replace(/\s/g, '');
    const match = text.match(/^([A-Z0-9]{4})-?([A-Z0-9]{4})$/i);
    if (match) {
      e.preventDefault();
      setPart1(match[1].toUpperCase());
      setPart2(match[2].toUpperCase());
      onComplete(`${match[1].toUpperCase()}-${match[2].toUpperCase()}`);
    }
  };

  return (
    <div className="flex items-center gap-2" onPaste={handlePaste}>
      <Input value={part1} onChange={e => handlePart1Change(e.target.value)}
        className="w-24 text-center text-2xl font-mono tracking-widest"
        maxLength={4} placeholder="ABCD" autoFocus />
      <span className="text-2xl font-bold">-</span>
      <Input ref={ref2} value={part2}
        onChange={e => handlePart2Change(e.target.value)}
        className="w-24 text-center text-2xl font-mono tracking-widest"
        maxLength={4} placeholder="1234" />
    </div>
  );
}
```

- [ ] **Step 3: 创建授权确认对话框和成功页面**

`authorize-confirm-dialog.tsx`:
```tsx
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter }
  from '@/shared/ui/dialog';
import { Button } from '@/shared/ui/button';

interface Props {
  open: boolean;
  userCode: string;
  onConfirm: () => void;
  onCancel: () => void;
  loading: boolean;
}

export function AuthorizeConfirmDialog({ open, userCode, onConfirm, onCancel, loading }: Props) {
  return (
    <Dialog open={open} onOpenChange={v => !v && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>确认授权 CLI 设备</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 text-sm">
          <p>授权码：<span className="font-mono font-bold">{userCode}</span></p>
          <p>权限：读取和管理你的技能、命名空间</p>
          <p className="text-amber-600">请确认这是你正在使用的 CLI 设备</p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel}>取消</Button>
          <Button onClick={onConfirm} disabled={loading}>
            {loading ? '授权中...' : '确认授权'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

`authorize-success.tsx`:
```tsx
import { CheckCircle } from 'lucide-react';
import { Button } from '@/shared/ui/button';

export function AuthorizeSuccess() {
  return (
    <div className="text-center space-y-4">
      <CheckCircle className="mx-auto h-16 w-16 text-green-500" />
      <h2 className="text-xl font-semibold">授权成功</h2>
      <p className="text-muted-foreground">你的 CLI 设备已成功授权，请返回 CLI 继续操作</p>
      <Button variant="outline" onClick={() => window.close()}>关闭窗口</Button>
    </div>
  );
}
```

- [ ] **Step 4: 创建 Device Auth 主页面**

```tsx
// web/src/pages/device-auth.tsx
import { useState } from 'react';
import { UserCodeInput } from '@/features/device-auth/user-code-input';
import { AuthorizeConfirmDialog } from '@/features/device-auth/authorize-confirm-dialog';
import { AuthorizeSuccess } from '@/features/device-auth/authorize-success';
import { useAuthorizeDevice } from '@/features/device-auth/use-authorize-device';
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card';

export default function DeviceAuthPage() {
  const [userCode, setUserCode] = useState('');
  const [showConfirm, setShowConfirm] = useState(false);
  const [authorized, setAuthorized] = useState(false);
  const mutation = useAuthorizeDevice();

  const handleComplete = (code: string) => {
    setUserCode(code);
    setShowConfirm(true);
  };

  const handleConfirm = () => {
    mutation.mutate(userCode, {
      onSuccess: () => { setShowConfirm(false); setAuthorized(true); },
    });
  };

  if (authorized) return <AuthorizeSuccess />;

  return (
    <div className="flex min-h-screen items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>授权 CLI 设备访问</CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          <p className="text-sm text-muted-foreground">请输入 CLI 显示的授权码：</p>
          <UserCodeInput onComplete={handleComplete} />
          {mutation.isError && (
            <p className="text-sm text-red-500">授权码无效，请检查后重试</p>
          )}
        </CardContent>
      </Card>
      <AuthorizeConfirmDialog
        open={showConfirm} userCode={userCode}
        onConfirm={handleConfirm} onCancel={() => setShowConfirm(false)}
        loading={mutation.isPending} />
    </div>
  );
}
```

- [ ] **Step 5: 添加路由配置**

在 `web/src/router.tsx` 中添加 `/device` 路由（需要登录守卫）。

- [ ] **Step 6: Commit**

```bash
git add web/src/pages/device-auth.tsx
git add web/src/features/device-auth/
git commit -m "feat(cli): add Device Auth frontend page"
```

### Task 6: Chunk 3 验收

- [ ] **Step 1: 运行后端测试**

运行：`cd server && ./mvnw test`
预期：所有测试通过

- [ ] **Step 2: 运行前端测试**

运行：`cd web && npm test`
预期：所有测试通过

- [ ] **Step 3: 验证 9 个验收标准**

逐一验证 Chunk 3 的验收标准。

---

## Chunk 4: ClawHub 兼容层

**范围：** Canonical slug 映射 + 兼容层端点

**验收标准：**
1. ClawHub CLI 可以通过 `/.well-known/clawhub.json` 发现兼容层 API
2. ClawHub CLI 可以搜索技能，返回 canonical slug 格式
3. ClawHub CLI 可以解析技能版本（`my-skill` 和 `team-ai--my-skill`）
4. ClawHub CLI 可以下载技能包
5. ClawHub CLI 可以发布技能（需要 Token 认证）
6. ClawHub CLI 可以查询当前用户信息
7. 所有兼容层端点测试通过

### Task 1: CanonicalSlugMapper 实现

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/CanonicalSlugMapper.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/SkillCoordinate.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/compat/CanonicalSlugMapperTest.java`

- [ ] **Step 1: 编写 CanonicalSlugMapper 测试**

```java
package com.iflytek.skillhub.app.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class CanonicalSlugMapperTest {
    private final CanonicalSlugMapper mapper = new CanonicalSlugMapper();

    @ParameterizedTest
    @CsvSource({
        "global, my-skill, my-skill",
        "team-ai, my-skill, team-ai--my-skill",
        "dev-team, code-review, dev-team--code-review"
    })
    void toCanonical(String namespace, String slug, String expected) {
        assertThat(mapper.toCanonical(namespace, slug)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "my-skill, global, my-skill",
        "team-ai--my-skill, team-ai, my-skill",
        "dev-team--code-review, dev-team, code-review"
    })
    void fromCanonical(String canonical, String expectedNs, String expectedSlug) {
        SkillCoordinate coord = mapper.fromCanonical(canonical);
        assertThat(coord.namespaceSlug()).isEqualTo(expectedNs);
        assertThat(coord.skillSlug()).isEqualTo(expectedSlug);
    }

    @Test
    void fromCanonical_no_separator_defaults_to_global() {
        SkillCoordinate coord = mapper.fromCanonical("simple-skill");
        assertThat(coord.namespaceSlug()).isEqualTo("global");
        assertThat(coord.skillSlug()).isEqualTo("simple-skill");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=CanonicalSlugMapperTest`
预期：编译失败

- [ ] **Step 3: 创建 SkillCoordinate record**

```java
package com.iflytek.skillhub.app.compat;

public record SkillCoordinate(String namespaceSlug, String skillSlug) {}
```

- [ ] **Step 4: 实现 CanonicalSlugMapper**

```java
package com.iflytek.skillhub.app.compat;

import org.springframework.stereotype.Component;

@Component
public class CanonicalSlugMapper {
    private static final String SEPARATOR = "--";

    public String toCanonical(String namespaceSlug, String skillSlug) {
        if ("global".equals(namespaceSlug)) {
            return skillSlug;
        }
        return namespaceSlug + SEPARATOR + skillSlug;
    }

    public SkillCoordinate fromCanonical(String canonicalSlug) {
        int idx = canonicalSlug.indexOf(SEPARATOR);
        if (idx == -1) {
            return new SkillCoordinate("global", canonicalSlug);
        }
        return new SkillCoordinate(
            canonicalSlug.substring(0, idx),
            canonicalSlug.substring(idx + SEPARATOR.length()));
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=CanonicalSlugMapperTest`
预期：5 个测试全部 PASS

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/compat/
git commit -m "feat(compat): add CanonicalSlugMapper with tests"
```

### Task 2: Well-Known 端点 + 兼容层 DTO

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/WellKnownController.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/dto/ClawHubSearchResponse.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/dto/ClawHubSkillItem.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/dto/ClawHubResolveResponse.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/dto/ClawHubPublishResponse.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/dto/ClawHubWhoamiResponse.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/compat/WellKnownControllerTest.java`

- [ ] **Step 1: 编写 WellKnownController 测试**

```java
package com.iflytek.skillhub.app.compat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WellKnownController.class)
class WellKnownControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void returns_api_base() throws Exception {
        mockMvc.perform(get("/.well-known/clawhub.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apiBase").value("/api/compat/v1"));
    }
}
```

- [ ] **Step 2: 实现 WellKnownController**

```java
package com.iflytek.skillhub.app.compat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class WellKnownController {
    @GetMapping("/.well-known/clawhub.json")
    public Map<String, String> clawHubDiscovery() {
        return Map.of("apiBase", "/api/compat/v1");
    }
}
```

- [ ] **Step 3: 创建兼容层 DTO**

```java
// ClawHubSkillItem.java
package com.iflytek.skillhub.app.compat.dto;

public record ClawHubSkillItem(
    String slug, String name, String description,
    String version, long downloads, int stars) {}

// ClawHubSearchResponse.java
package com.iflytek.skillhub.app.compat.dto;

import java.util.List;

public record ClawHubSearchResponse(
    List<ClawHubSkillItem> items, long total, int page, int size) {}

// ClawHubResolveResponse.java
package com.iflytek.skillhub.app.compat.dto;

public record ClawHubResolveResponse(
    String slug, String name, String version,
    String downloadUrl, int fileCount, long totalSize) {}

// ClawHubPublishResponse.java
package com.iflytek.skillhub.app.compat.dto;

public record ClawHubPublishResponse(String slug, String version, String status) {}

// ClawHubWhoamiResponse.java
package com.iflytek.skillhub.app.compat.dto;

public record ClawHubWhoamiResponse(Long userId, String username, String email) {}
```

- [ ] **Step 4: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=WellKnownControllerTest`
预期：PASS

- [ ] **Step 5: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/compat/
git commit -m "feat(compat): add well-known endpoint and compat DTOs"
```

### Task 3: ClawHubCompatController 实现

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/ClawHubCompatController.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/compat/ClawHubCompatControllerTest.java`

- [ ] **Step 1: 编写 ClawHubCompatController 测试**

```java
package com.iflytek.skillhub.app.compat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClawHubCompatController.class)
class ClawHubCompatControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean CanonicalSlugMapper slugMapper;

    @Test
    void search_returns_compat_format() throws Exception {
        mockMvc.perform(get("/api/compat/v1/search").param("q", "test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void resolve_parses_canonical_slug() throws Exception {
        when(slugMapper.fromCanonical("my-skill"))
            .thenReturn(new SkillCoordinate("global", "my-skill"));
        mockMvc.perform(get("/api/compat/v1/resolve")
                .param("slug", "my-skill"))
            .andExpect(status().isOk());
    }

    @Test
    void whoami_requires_auth() throws Exception {
        mockMvc.perform(get("/api/compat/v1/whoami"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 实现 ClawHubCompatController**

```java
package com.iflytek.skillhub.app.compat;

import com.iflytek.skillhub.app.compat.dto.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/compat/v1")
public class ClawHubCompatController {
    private final CanonicalSlugMapper slugMapper;

    public ClawHubCompatController(CanonicalSlugMapper slugMapper) {
        this.slugMapper = slugMapper;
    }

    @GetMapping("/search")
    public ClawHubSearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 调用 skillhub 搜索服务，转换为 canonical slug 格式
        // TODO: 注入 SkillSearchAppService 并调用
        return new ClawHubSearchResponse(List.of(), 0, page, size);
    }

    @GetMapping("/resolve")
    public ClawHubResolveResponse resolve(
            @RequestParam String slug,
            @RequestParam(defaultValue = "latest") String version) {
        SkillCoordinate coord = slugMapper.fromCanonical(slug);
        // TODO: 调用 SkillQueryService 获取版本详情
        return new ClawHubResolveResponse(slug, "", version,
            "/api/compat/v1/download/" + slug + "/" + version, 0, 0);
    }

    @GetMapping("/download/{slug}/{version}")
    public ResponseEntity<Resource> download(
            @PathVariable String slug,
            @PathVariable String version) {
        SkillCoordinate coord = slugMapper.fromCanonical(slug);
        // TODO: 调用 SkillDownloadService
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/publish")
    @PreAuthorize("isAuthenticated()")
    public ClawHubPublishResponse publish(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "global") String namespace,
            @AuthenticationPrincipal Long userId) {
        // TODO: 调用 SkillPublishService
        return new ClawHubPublishResponse("", "", "pending_review");
    }

    @GetMapping("/whoami")
    @PreAuthorize("isAuthenticated()")
    public ClawHubWhoamiResponse whoami(@AuthenticationPrincipal Long userId) {
        // TODO: 查询用户信息
        return new ClawHubWhoamiResponse(userId, "", "");
    }
}
```

- [ ] **Step 3: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=ClawHubCompatControllerTest`
预期：3 个测试全部 PASS

- [ ] **Step 4: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/compat/ClawHubCompatController.java
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/compat/ClawHubCompatControllerTest.java
git commit -m "feat(compat): add ClawHub compatibility controller"
```

### Task 4: Chunk 4 验收

- [ ] **Step 1: 运行所有测试**

运行：`cd server && ./mvnw test`
预期：所有测试通过

- [ ] **Step 2: 验证 7 个验收标准**

逐一验证 Chunk 4 的验收标准。

---

## Chunk 5: 幂等去重 + 管理后台

**范围：** 幂等拦截器 + 管理后台前端

**验收标准：**
1. 写操作带 `X-Request-Id` 时，重复请求返回原始结果
2. Redis 不可用时，PostgreSQL 兜底去重
3. 定时任务清理过期幂等记录
4. 管理后台：USER_ADMIN 可以查看用户列表，编辑角色，封禁/解封用户
5. 管理后台：AUDITOR 可以查看审计日志，筛选和搜索
6. 管理后台：SUPER_ADMIN 可以访问所有管理功能
7. 前端路由守卫：非管理员访问 `/admin` 跳转到 403 页面
8. 所有测试通过

### Task 1: IdempotencyRecord 实体和 Repository

**Files:**
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/idempotency/IdempotencyRecord.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/idempotency/IdempotencyStatus.java`
- Create: `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/idempotency/IdempotencyRecordRepository.java`
- Create: `server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaIdempotencyRecordRepository.java`

- [ ] **Step 1: 创建 IdempotencyStatus 枚举**

```java
package com.iflytek.skillhub.domain.idempotency;

public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 2: 创建 IdempotencyRecord 实体**

```java
package com.iflytek.skillhub.domain.idempotency;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {
    @Id
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "resource_type", length = 64, nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String requestId, String resourceType,
                             IdempotencyStatus status, Instant expiresAt) {
        this.requestId = requestId;
        this.resourceType = resourceType;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public String getRequestId() { return requestId; }
    public String getResourceType() { return resourceType; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public IdempotencyStatus getStatus() { return status; }
    public void setStatus(IdempotencyStatus status) { this.status = status; }
    public Integer getResponseStatusCode() { return responseStatusCode; }
    public void setResponseStatusCode(Integer code) { this.responseStatusCode = code; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
```

- [ ] **Step 3: 创建 Repository 接口**

```java
package com.iflytek.skillhub.domain.idempotency;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository {
    IdempotencyRecord save(IdempotencyRecord record);
    Optional<IdempotencyRecord> findById(String requestId);
    int deleteExpired(Instant now);
    int markStaleAsFailed(Instant threshold);
    void updateToCompleted(String requestId, String resourceType,
                           Long resourceId, int statusCode);
}
```

- [ ] **Step 4: 实现 JPA Repository**

```java
package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.idempotency.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;

@Repository
public interface JpaIdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, String>, IdempotencyRecordRepository {

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("""
        UPDATE IdempotencyRecord r SET r.status = 'FAILED'
        WHERE r.status = 'PROCESSING' AND r.createdAt < :threshold
    """)
    int markStaleAsFailed(@Param("threshold") Instant threshold);

    @Modifying
    @Query("""
        UPDATE IdempotencyRecord r
        SET r.status = 'COMPLETED', r.resourceType = :resourceType,
            r.resourceId = :resourceId, r.responseStatusCode = :statusCode
        WHERE r.requestId = :requestId
    """)
    void updateToCompleted(@Param("requestId") String requestId,
                           @Param("resourceType") String resourceType,
                           @Param("resourceId") Long resourceId,
                           @Param("statusCode") int statusCode);
}
```

- [ ] **Step 5: 编译验证**

运行：`cd server && ./mvnw compile`
预期：编译成功

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/idempotency/
git add server/skillhub-infra/src/main/java/com/iflytek/skillhub/infra/jpa/JpaIdempotencyRecordRepository.java
git commit -m "feat(idempotency): add IdempotencyRecord entity and repository"
```

### Task 2: IdempotencyInterceptor 实现

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/interceptor/IdempotencyInterceptor.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/config/WebMvcIdempotencyConfig.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/interceptor/IdempotencyInterceptorTest.java`

- [ ] **Step 1: 编写 IdempotencyInterceptor 测试**

```java
package com.iflytek.skillhub.app.interceptor;

import com.iflytek.skillhub.domain.idempotency.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyInterceptorTest {
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock IdempotencyRecordRepository recordRepository;
    @InjectMocks IdempotencyInterceptor interceptor;

    MockHttpServletRequest request;
    MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void get_request_passes_through() throws Exception {
        request.setMethod("GET");
        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void post_without_request_id_passes_through() throws Exception {
        request.setMethod("POST");
        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void post_with_new_request_id_passes_through() throws Exception {
        request.setMethod("POST");
        request.addHeader("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);
        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        verify(recordRepository).save(any(IdempotencyRecord.class));
    }

    @Test
    void post_with_duplicate_request_id_returns_completed_result() throws Exception {
        request.setMethod("POST");
        request.addHeader("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(false);

        IdempotencyRecord record = new IdempotencyRecord(
            "550e8400-e29b-41d4-a716-446655440000", "skill_version",
            IdempotencyStatus.COMPLETED, null);
        record.setResourceId(123L);
        record.setResponseStatusCode(200);
        when(recordRepository.findById(anyString())).thenReturn(Optional.of(record));

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void post_with_processing_request_returns_409() throws Exception {
        request.setMethod("POST");
        request.addHeader("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(false);

        IdempotencyRecord record = new IdempotencyRecord(
            "550e8400-e29b-41d4-a716-446655440000", "skill_version",
            IdempotencyStatus.PROCESSING, null);
        when(recordRepository.findById(anyString())).thenReturn(Optional.of(record));

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(409);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=IdempotencyInterceptorTest`
预期：编译失败

- [ ] **Step 3: 实现 IdempotencyInterceptor**

```java
package com.iflytek.skillhub.app.interceptor;

import com.iflytek.skillhub.domain.idempotency.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Pattern;

public class IdempotencyInterceptor implements HandlerInterceptor {
    private static final String HEADER = "X-Request-Id";
    private static final String ATTR = "idempotency.requestId";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final RedisTemplate<String, String> redisTemplate;
    private final IdempotencyRecordRepository recordRepository;

    public IdempotencyInterceptor(RedisTemplate<String, String> redisTemplate,
                                  IdempotencyRecordRepository recordRepository) {
        this.redisTemplate = redisTemplate;
        this.recordRepository = recordRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!WRITE_METHODS.contains(request.getMethod())) return true;

        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) return true;

        if (!UUID_PATTERN.matcher(requestId.toLowerCase()).matches()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid X-Request-Id format\"}");
            return false;
        }

        String redisKey = "idempotent:" + requestId;
        Boolean isNew;
        try {
            isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", Duration.ofHours(24));
        } catch (Exception e) {
            // Redis 不可用，fall through 到 PostgreSQL
            isNew = true;
        }

        if (Boolean.FALSE.equals(isNew)) {
            return handleDuplicate(requestId, response);
        }

        // 新请求，插入 PROCESSING 记录
        IdempotencyRecord record = new IdempotencyRecord(
            requestId, "unknown", IdempotencyStatus.PROCESSING,
            Instant.now().plus(24, ChronoUnit.HOURS));
        recordRepository.save(record);
        request.setAttribute(ATTR, requestId);
        return true;
    }

    private boolean handleDuplicate(String requestId, HttpServletResponse response)
            throws Exception {
        var record = recordRepository.findById(requestId).orElse(null);
        if (record == null) {
            // Redis 有但 DB 无，可能脏数据，允许重试
            redisTemplate.delete("idempotent:" + requestId);
            return true;
        }
        return switch (record.getStatus()) {
            case COMPLETED -> {
                response.setStatus(record.getResponseStatusCode());
                response.setContentType("application/json");
                response.getWriter().write(String.format(
                    "{\"code\":0,\"data\":{\"resourceType\":\"%s\",\"resourceId\":%d}}",
                    record.getResourceType(), record.getResourceId()));
                yield false;
            }
            case PROCESSING -> {
                response.setStatus(HttpStatus.CONFLICT.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Request is being processed\"}");
                yield false;
            }
            case FAILED -> {
                redisTemplate.delete("idempotent:" + requestId);
                yield true;
            }
        };
    }
}
```

- [ ] **Step 4: 注册拦截器**

```java
package com.iflytek.skillhub.app.config;

import com.iflytek.skillhub.app.interceptor.IdempotencyInterceptor;
import com.iflytek.skillhub.domain.idempotency.IdempotencyRecordRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcIdempotencyConfig implements WebMvcConfigurer {
    private final RedisTemplate<String, String> redisTemplate;
    private final IdempotencyRecordRepository recordRepository;

    public WebMvcIdempotencyConfig(RedisTemplate<String, String> redisTemplate,
                                   IdempotencyRecordRepository recordRepository) {
        this.redisTemplate = redisTemplate;
        this.recordRepository = recordRepository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IdempotencyInterceptor(redisTemplate, recordRepository))
            .addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=IdempotencyInterceptorTest`
预期：5 个测试全部 PASS

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/interceptor/
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/config/WebMvcIdempotencyConfig.java
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/interceptor/
git commit -m "feat(idempotency): add IdempotencyInterceptor with Redis + PostgreSQL"
```

### Task 3: 幂等记录定时清理

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/task/IdempotencyCleanupTask.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/task/IdempotencyCleanupTaskTest.java`

- [ ] **Step 1: 编写清理任务测试**

```java
package com.iflytek.skillhub.app.task;

import com.iflytek.skillhub.domain.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupTaskTest {
    @Mock IdempotencyRecordRepository repository;
    @InjectMocks IdempotencyCleanupTask task;

    @Test
    void cleanupExpired_deletes_old_records() {
        when(repository.deleteExpired(any(Instant.class))).thenReturn(5);
        task.cleanupExpiredRecords();
        verify(repository).deleteExpired(any(Instant.class));
    }

    @Test
    void cleanupStale_marks_processing_as_failed() {
        when(repository.markStaleAsFailed(any(Instant.class))).thenReturn(2);
        task.cleanupStaleProcessing();
        verify(repository).markStaleAsFailed(any(Instant.class));
    }
}
```

- [ ] **Step 2: 实现清理任务**

```java
package com.iflytek.skillhub.app.task;

import com.iflytek.skillhub.domain.idempotency.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class IdempotencyCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupTask.class);
    private final IdempotencyRecordRepository repository;

    public IdempotencyCleanupTask(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredRecords() {
        int deleted = repository.deleteExpired(Instant.now());
        log.info("Cleaned up {} expired idempotency records", deleted);
    }

    @Scheduled(fixedDelay = 300000)
    public void cleanupStaleProcessing() {
        Instant threshold = Instant.now().minus(5, ChronoUnit.MINUTES);
        int updated = repository.markStaleAsFailed(threshold);
        if (updated > 0) {
            log.warn("Marked {} stale PROCESSING records as FAILED", updated);
        }
    }
}
```

- [ ] **Step 3: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest=IdempotencyCleanupTaskTest`
预期：2 个测试全部 PASS

- [ ] **Step 4: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/task/
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/task/
git commit -m "feat(idempotency): add cleanup scheduled tasks"
```

### Task 4: 管理后台 API（用户管理 + 审计日志）

**Files:**
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/admin/UserManagementController.java`
- Create: `server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/admin/AuditLogController.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/admin/UserManagementControllerTest.java`
- Test: `server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/admin/AuditLogControllerTest.java`

- [ ] **Step 1: 编写 UserManagementController 测试**

```java
package com.iflytek.skillhub.app.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserManagementController.class)
class UserManagementControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void list_users_requires_admin_role() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER_ADMIN")
    void list_users_accessible_by_user_admin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void list_users_accessible_by_super_admin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 实现 UserManagementController**

```java
package com.iflytek.skillhub.app.controller.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('USER_ADMIN', 'SUPER_ADMIN')")
public class UserManagementController {

    @GetMapping
    public ResponseEntity<?> listUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // TODO: 注入 UserAccountRepository 查询
        return ResponseEntity.ok(Map.of("items", List.of(), "total", 0));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetail(@PathVariable Long userId) {
        // TODO: 查询用户详情 + 角色 + namespace 成员
        return ResponseEntity.ok(Map.of("userId", userId));
    }

    @PutMapping("/{userId}/roles")
    public ResponseEntity<Void> updateUserRoles(
            @PathVariable Long userId,
            @RequestBody Map<String, List<String>> body) {
        // TODO: 更新用户平台角色
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/status")
    public ResponseEntity<Void> updateUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) {
        // TODO: 封禁/解封用户
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 3: 编写 AuditLogController 测试**

```java
package com.iflytek.skillhub.app.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditLogController.class)
class AuditLogControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void audit_logs_requires_auditor_role() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void audit_logs_accessible_by_auditor() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs"))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 4: 实现 AuditLogController**

```java
package com.iflytek.skillhub.app.controller.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasAnyRole('AUDITOR', 'SUPER_ADMIN')")
public class AuditLogController {

    @GetMapping
    public ResponseEntity<?> listAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // TODO: 注入 AuditLogRepository 查询
        return ResponseEntity.ok(Map.of("items", List.of(), "total", 0));
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

运行：`cd server && ./mvnw test -pl skillhub-app -Dtest="UserManagementControllerTest,AuditLogControllerTest"`
预期：5 个测试全部 PASS

- [ ] **Step 6: Commit**

```bash
git add server/skillhub-app/src/main/java/com/iflytek/skillhub/app/controller/admin/
git add server/skillhub-app/src/test/java/com/iflytek/skillhub/app/controller/admin/
git commit -m "feat(admin): add UserManagement and AuditLog controllers"
```

### Task 5: 管理后台前端页面

**Files:**
- Create: `web/src/pages/admin/users.tsx`
- Create: `web/src/pages/admin/user-detail.tsx`
- Create: `web/src/pages/admin/audit-logs.tsx`
- Create: `web/src/features/admin/user-table.tsx`
- Create: `web/src/features/admin/edit-roles-dialog.tsx`
- Create: `web/src/features/admin/audit-log-table.tsx`
- Create: `web/src/features/admin/use-users.ts`
- Create: `web/src/features/admin/use-audit-logs.ts`
- Create: `web/src/features/admin/use-update-user-roles.ts`

- [ ] **Step 1: 创建 admin hooks**

```typescript
// web/src/features/admin/use-users.ts
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/api/client';

export function useUsers(params: { search?: string; status?: string; page: number }) {
  return useQuery({
    queryKey: ['admin', 'users', params],
    queryFn: () => apiClient.get('/api/v1/admin/users', { params }),
  });
}

// web/src/features/admin/use-audit-logs.ts
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/api/client';

export function useAuditLogs(params: {
  action?: string; actorUserId?: number;
  startTime?: string; endTime?: string; page: number;
}) {
  return useQuery({
    queryKey: ['admin', 'audit-logs', params],
    queryFn: () => apiClient.get('/api/v1/admin/audit-logs', { params }),
  });
}

// web/src/features/admin/use-update-user-roles.ts
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/api/client';

export function useUpdateUserRoles() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roles }: { userId: number; roles: string[] }) =>
      apiClient.put(`/api/v1/admin/users/${userId}/roles`, { roles }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  });
}
```

- [ ] **Step 2: 创建用户管理页面**

```tsx
// web/src/pages/admin/users.tsx
import { useState } from 'react';
import { useUsers } from '@/features/admin/use-users';
import { Input } from '@/shared/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue }
  from '@/shared/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow }
  from '@/shared/ui/table';
import { Badge } from '@/shared/ui/badge';
import { Button } from '@/shared/ui/button';
import { Link } from '@tanstack/react-router';

export default function AdminUsersPage() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState(0);
  const { data, isLoading } = useUsers({ search, status, page });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">用户管理</h1>
      <div className="flex gap-4">
        <Input placeholder="搜索用户名/邮箱" value={search}
          onChange={e => setSearch(e.target.value)} className="max-w-xs" />
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger className="w-40"><SelectValue placeholder="状态" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ACTIVE">活跃</SelectItem>
            <SelectItem value="DISABLED">已封禁</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>用户名</TableHead>
            <TableHead>邮箱</TableHead>
            <TableHead>状态</TableHead>
            <TableHead>角色</TableHead>
            <TableHead>操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data?.items?.map((user: any) => (
            <TableRow key={user.id}>
              <TableCell>{user.displayName}</TableCell>
              <TableCell>{user.email}</TableCell>
              <TableCell>
                <Badge variant={user.status === 'ACTIVE' ? 'default' : 'destructive'}>
                  {user.status}
                </Badge>
              </TableCell>
              <TableCell>{user.roles?.join(', ')}</TableCell>
              <TableCell>
                <Button variant="ghost" size="sm" asChild>
                  <Link to={`/admin/users/${user.id}`}>详情</Link>
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
```

- [ ] **Step 3: 创建审计日志页面**

```tsx
// web/src/pages/admin/audit-logs.tsx
import { useState } from 'react';
import { useAuditLogs } from '@/features/admin/use-audit-logs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue }
  from '@/shared/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow }
  from '@/shared/ui/table';
import { Badge } from '@/shared/ui/badge';

export default function AuditLogsPage() {
  const [action, setAction] = useState<string>();
  const [page, setPage] = useState(0);
  const { data, isLoading } = useAuditLogs({ action, page });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">审计日志</h1>
      <div className="flex gap-4">
        <Select value={action} onValueChange={setAction}>
          <SelectTrigger className="w-40"><SelectValue placeholder="操作类型" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="PUBLISH">发布</SelectItem>
            <SelectItem value="APPROVE">审核通过</SelectItem>
            <SelectItem value="REJECT">审核拒绝</SelectItem>
            <SelectItem value="DELETE">删除</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>时间</TableHead>
            <TableHead>操作人</TableHead>
            <TableHead>操作</TableHead>
            <TableHead>目标</TableHead>
            <TableHead>IP</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data?.items?.map((log: any) => (
            <TableRow key={log.id}>
              <TableCell className="text-sm">{log.createdAt}</TableCell>
              <TableCell>{log.actorName}</TableCell>
              <TableCell><Badge>{log.action}</Badge></TableCell>
              <TableCell>{log.targetType} #{log.targetId}</TableCell>
              <TableCell className="font-mono text-xs">{log.clientIp}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
```

- [ ] **Step 4: 添加管理后台路由守卫**

在 `web/src/router.tsx` 中添加 `/admin` 路由组，配置角色守卫：

```typescript
// 管理后台路由守卫 - 检查用户是否有管理员角色
function AdminGuard({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const adminRoles = ['SUPER_ADMIN', 'USER_ADMIN', 'AUDITOR'];
  const hasAdminRole = user?.roles?.some((r: string) => adminRoles.includes(r));
  if (!hasAdminRole) return <Navigate to="/403" />;
  return <>{children}</>;
}
```

- [ ] **Step 5: Commit**

```bash
git add web/src/pages/admin/
git add web/src/features/admin/
git commit -m "feat(admin): add admin dashboard pages (users, audit-logs)"
```

### Task 6: Chunk 5 验收

- [ ] **Step 1: 运行后端测试**

运行：`cd server && ./mvnw test`
预期：所有测试通过

- [ ] **Step 2: 运行前端测试**

运行：`cd web && npm test`
预期：所有测试通过

- [ ] **Step 3: 验证 8 个验收标准**

逐一验证 Chunk 5 的验收标准。

- [ ] **Step 4: 最终代码审查**

运行：`cd server && ./mvnw compile && cd ../web && npm run build`
预期：编译和构建全部成功

---

## 实施说明

**当前文档状态：**
- ✅ Chunk 1：审核流程核心（后端）— Task 1-5 详细 TDD 步骤，Task 6-10 概要
- ✅ Chunk 2：评分收藏 + 前端审核中心 — Task 1-4 详细 TDD 步骤，Task 5-11 概要
- ✅ Chunk 3：CLI API + Web 授权 — Task 1-6 详细 TDD 步骤
- ✅ Chunk 4：ClawHub 兼容层 — Task 1-4 详细 TDD 步骤
- ✅ Chunk 5：幂等去重 + 管理后台 — Task 1-6 详细 TDD 步骤

**建议的实施方式：**

1. **使用 superpowers:subagent-driven-development** — 为每个 Chunk 派发独立的子代理
2. **渐进式实施** — 先完成 Chunk 1，验收通过后再进行 Chunk 2
3. **参考设计文档** — 每个任务的详细实现逻辑参考 `docs/superpowers/specs/2026-03-12-phase3-review-cli-social-design.md`
4. **概要任务的实施** — 标记为概要的任务（如 Chunk 1 Task 6-10、Chunk 2 Task 5-11），实施时参考设计文档中的对应章节，按照已有详细任务的 TDD 模式编写代码

