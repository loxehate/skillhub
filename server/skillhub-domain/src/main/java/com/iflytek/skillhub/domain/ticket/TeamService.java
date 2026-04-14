package com.iflytek.skillhub.domain.ticket;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final NamespaceRepository namespaceRepository;
    private final TicketPermissionChecker permissionChecker = new TicketPermissionChecker();

    public TeamService(TeamRepository teamRepository,
                       TeamMemberRepository teamMemberRepository,
                       NamespaceRepository namespaceRepository) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.namespaceRepository = namespaceRepository;
    }

    @Transactional
    public Team createTeam(String name,
                           String namespaceSlug,
                           String ownerId,
                           Map<Long, NamespaceRole> userNsRoles,
                           Set<String> platformRoles) {
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
        NamespaceRole namespaceRole = resolveNamespaceRole(namespace.getId(), userNsRoles);
        if (!permissionChecker.canCreate(platformRoles, namespaceRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }

        Team team = teamRepository.save(new Team(name, ownerId, namespace.getId()));
        teamMemberRepository.save(new TeamMember(team.getId(), ownerId, TeamRole.ADMIN));
        return team;
    }

    @Transactional
    public TeamMember addMember(Long teamId,
                                String userId,
                                TeamRole role,
                                String operatorId,
                                Map<Long, NamespaceRole> userNsRoles,
                                Set<String> platformRoles) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new DomainBadRequestException("error.team.notFound", teamId));
        NamespaceRole namespaceRole = resolveNamespaceRole(team.getNamespaceId(), userNsRoles);
        TeamRole operatorTeamRole = resolveTeamRole(teamId, operatorId);
        if (!canManageTeam(platformRoles, namespaceRole, operatorTeamRole, team.getOwnerId(), operatorId)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        return teamMemberRepository.save(new TeamMember(teamId, userId, role));
    }

    public Team getTeam(Long teamId, String userId, Map<Long, NamespaceRole> userNsRoles, Set<String> platformRoles) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new DomainBadRequestException("error.team.notFound", teamId));
        NamespaceRole namespaceRole = resolveNamespaceRole(team.getNamespaceId(), userNsRoles);
        if (!permissionChecker.canView(platformRoles, namespaceRole)
                && teamMemberRepository.findByTeamIdAndUserId(teamId, userId).isEmpty()) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        return team;
    }

    public List<Team> listTeams(Long namespaceId) {
        return teamRepository.findByNamespaceId(namespaceId);
    }

    public List<TeamMember> listTeamMembers(Long teamId) {
        return teamMemberRepository.findByTeamId(teamId);
    }

    private NamespaceRole resolveNamespaceRole(Long namespaceId, Map<Long, NamespaceRole> userNsRoles) {
        return userNsRoles != null ? userNsRoles.get(namespaceId) : null;
    }

    private TeamRole resolveTeamRole(Long teamId, String userId) {
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .map(TeamMember::getRole)
                .orElse(null);
    }

    private boolean canManageTeam(Set<String> platformRoles,
                                  NamespaceRole namespaceRole,
                                  TeamRole teamRole,
                                  String ownerId,
                                  String operatorId) {
        if (ownerId != null && ownerId.equals(operatorId)) {
            return true;
        }
        if (teamRole == TeamRole.ADMIN) {
            return true;
        }
        return permissionChecker.canManage(platformRoles, namespaceRole);
    }
}
