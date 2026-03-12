package com.iflytek.skillhub.domain.review;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ReviewPermissionChecker {

    public boolean canSubmitReview(Long namespaceId,
                                   Map<Long, NamespaceRole> userNamespaceRoles) {
        NamespaceRole role = userNamespaceRoles.get(namespaceId);
        return role == NamespaceRole.OWNER
                || role == NamespaceRole.ADMIN
                || role == NamespaceRole.MEMBER;
    }

    public boolean canManageNamespaceReviews(Long namespaceId,
                                             NamespaceType namespaceType,
                                             Map<Long, NamespaceRole> userNamespaceRoles,
                                             Set<String> platformRoles) {
        if (namespaceType == NamespaceType.GLOBAL) {
            return hasPlatformReviewRole(platformRoles);
        }

        NamespaceRole role = userNamespaceRoles.get(namespaceId);
        return role == NamespaceRole.OWNER || role == NamespaceRole.ADMIN;
    }

    /**
     * Check if a user can review a ReviewTask.
     *
     * @param task               the review task
     * @param userId             the reviewer's user ID
     * @param namespaceType      the type of the namespace
     * @param userNamespaceRoles user's roles keyed by namespace ID
     * @param platformRoles      user's platform-level roles
     * @return true if the user is allowed to review
     */
    public boolean canReview(ReviewTask task,
                             String userId,
                             NamespaceType namespaceType,
                             Map<Long, NamespaceRole> userNamespaceRoles,
                             Set<String> platformRoles) {
        // Cannot review own submission
        if (task.getSubmittedBy().equals(userId)) {
            return false;
        }

        // Global namespace: only SKILL_ADMIN or SUPER_ADMIN
        return canManageNamespaceReviews(
                task.getNamespaceId(),
                namespaceType,
                userNamespaceRoles,
                platformRoles
        );
    }

    public boolean canReadReview(ReviewTask task,
                                 String userId,
                                 NamespaceType namespaceType,
                                 Map<Long, NamespaceRole> userNamespaceRoles,
                                 Set<String> platformRoles) {
        return task.getSubmittedBy().equals(userId)
                || canManageNamespaceReviews(
                        task.getNamespaceId(),
                        namespaceType,
                        userNamespaceRoles,
                        platformRoles
                );
    }

    public boolean canSubmitPromotion(Skill sourceSkill,
                                      String userId,
                                      Map<Long, NamespaceRole> userNamespaceRoles) {
        if (sourceSkill.getOwnerId().equals(userId)) {
            return true;
        }

        NamespaceRole role = userNamespaceRoles.get(sourceSkill.getNamespaceId());
        return role == NamespaceRole.OWNER || role == NamespaceRole.ADMIN;
    }

    /**
     * Check if a user can review a PromotionRequest.
     * Only SKILL_ADMIN or SUPER_ADMIN, and not own.
     */
    public boolean canReviewPromotion(
            PromotionRequest request,
            String userId,
            Set<String> platformRoles) {
        if (request.getSubmittedBy().equals(userId)) {
            return false;
        }
        return hasPlatformReviewRole(platformRoles);
    }

    public boolean canListPendingPromotions(Set<String> platformRoles) {
        return hasPlatformReviewRole(platformRoles);
    }

    public boolean canReadPromotion(PromotionRequest request,
                                    String userId,
                                    Set<String> platformRoles) {
        return request.getSubmittedBy().equals(userId)
                || canListPendingPromotions(platformRoles);
    }

    private boolean hasPlatformReviewRole(Set<String> platformRoles) {
        return platformRoles.contains("SKILL_ADMIN")
                || platformRoles.contains("SUPER_ADMIN");
    }
}
