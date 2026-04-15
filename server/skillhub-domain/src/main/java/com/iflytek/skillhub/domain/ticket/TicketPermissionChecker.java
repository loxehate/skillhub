package com.iflytek.skillhub.domain.ticket;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import java.util.Set;

public class TicketPermissionChecker {

    public boolean canCreate(Set<String> platformRoles, NamespaceRole namespaceRole) {
        return isSuperAdmin(platformRoles)
                || platformRoles.contains("USER_ADMIN")
                || namespaceRole == NamespaceRole.OWNER
                || namespaceRole == NamespaceRole.ADMIN;
    }

    public boolean canView(Set<String> platformRoles, NamespaceRole namespaceRole) {
        return isSuperAdmin(platformRoles)
                || platformRoles.contains("SKILL_ADMIN")
                || platformRoles.contains("AUDITOR")
                || namespaceRole == NamespaceRole.OWNER
                || namespaceRole == NamespaceRole.ADMIN;
    }

    public boolean canClaim(Set<String> platformRoles, NamespaceRole namespaceRole) {
        return isSuperAdmin(platformRoles)
                || namespaceRole == NamespaceRole.OWNER
                || namespaceRole == NamespaceRole.ADMIN
                || namespaceRole == NamespaceRole.MEMBER;
    }

    public boolean canDevelop(Set<String> platformRoles, NamespaceRole namespaceRole, TeamRole teamRole) {
        return isSuperAdmin(platformRoles)
                || namespaceRole == NamespaceRole.OWNER
                || namespaceRole == NamespaceRole.ADMIN
                || namespaceRole == NamespaceRole.MEMBER
                || teamRole == TeamRole.ADMIN
                || teamRole == TeamRole.DEV;
    }

    public boolean canReview(Set<String> platformRoles, NamespaceRole namespaceRole, TeamRole teamRole) {
        return isSuperAdmin(platformRoles)
                || namespaceRole == NamespaceRole.OWNER
                || namespaceRole == NamespaceRole.ADMIN
                || teamRole == TeamRole.ADMIN;
    }

    public boolean canReject(Set<String> platformRoles, NamespaceRole namespaceRole, TeamRole teamRole) {
        return isSuperAdmin(platformRoles) || platformRoles.contains("USER_ADMIN");
    }

    public boolean canSubmitSkill(Set<String> platformRoles, NamespaceRole namespaceRole, TeamRole teamRole) {
        return isSuperAdmin(platformRoles)
                || platformRoles.contains("USER_ADMIN")
                || namespaceRole == NamespaceRole.OWNER
                || namespaceRole == NamespaceRole.ADMIN
                || namespaceRole == NamespaceRole.MEMBER
                || teamRole == TeamRole.ADMIN
                || teamRole == TeamRole.DEV;
    }

    public boolean canManage(Set<String> platformRoles, NamespaceRole namespaceRole) {
        return isSuperAdmin(platformRoles)
                || platformRoles.contains("USER_ADMIN");
    }

    public boolean canArbitrate(Set<String> platformRoles) {
        return isSuperAdmin(platformRoles) || platformRoles.contains("SKILL_ADMIN");
    }

    private boolean isSuperAdmin(Set<String> platformRoles) {
        return platformRoles != null && platformRoles.contains("SUPER_ADMIN");
    }
}
