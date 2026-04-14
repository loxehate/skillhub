package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.ticket.Team;
import com.iflytek.skillhub.domain.ticket.TeamMember;
import com.iflytek.skillhub.domain.ticket.TeamRole;
import com.iflytek.skillhub.domain.ticket.TeamService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.TeamCreateRequest;
import com.iflytek.skillhub.dto.TeamMemberAddRequest;
import com.iflytek.skillhub.dto.TeamMemberResponse;
import com.iflytek.skillhub.dto.TeamResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/teams", "/api/web/teams"})
public class TeamController extends BaseApiController {

    private final TeamService teamService;

    public TeamController(TeamService teamService, ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.teamService = teamService;
    }

    @PostMapping
    public ApiResponse<TeamResponse> createTeam(
            @Valid @RequestBody TeamCreateRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Team team = teamService.createTeam(
                request.name(),
                request.namespace(),
                principal.userId(),
                userNsRoles,
                principal.platformRoles()
        );
        return ok("response.success.created", toResponse(team));
    }

    @GetMapping("/{teamId}")
    public ApiResponse<TeamResponse> getTeam(
            @PathVariable Long teamId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Team team = teamService.getTeam(teamId, principal.userId(), userNsRoles, principal.platformRoles());
        return ok("response.success.read", toResponse(team));
    }

    @GetMapping("/{teamId}/members")
    public ApiResponse<List<TeamMemberResponse>> listMembers(@PathVariable Long teamId) {
        List<TeamMemberResponse> members = teamService.listTeamMembers(teamId).stream()
                .map(this::toResponse)
                .toList();
        return ok("response.success.read", members);
    }

    @PostMapping("/{teamId}/members")
    public ApiResponse<TeamMemberResponse> addMember(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamMemberAddRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        TeamRole role = parseRole(request.role());
        TeamMember member = teamService.addMember(
                teamId,
                request.userId(),
                role,
                principal.userId(),
                userNsRoles,
                principal.platformRoles()
        );
        return ok("response.success.created", toResponse(member));
    }

    @GetMapping
    public ApiResponse<List<TeamResponse>> listTeams(
            @RequestParam(value = "namespaceId", required = false) Long namespaceId) {
        if (namespaceId == null) {
            return ok("response.success.read", List.of());
        }
        List<TeamResponse> teams = teamService.listTeams(namespaceId).stream()
                .map(this::toResponse)
                .toList();
        return ok("response.success.read", teams);
    }

    private TeamResponse toResponse(Team team) {
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getOwnerId(),
                team.getNamespaceId(),
                team.getCreatedAt(),
                team.getUpdatedAt()
        );
    }

    private TeamMemberResponse toResponse(TeamMember member) {
        return new TeamMemberResponse(
                member.getTeamId(),
                member.getUserId(),
                member.getRole().name(),
                member.getCreatedAt()
        );
    }

    private TeamRole parseRole(String role) {
        try {
            return TeamRole.valueOf(role.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new DomainBadRequestException("error.team.role.invalid", role);
        }
    }
}
