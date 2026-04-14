package com.iflytek.skillhub.domain.ticket;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketClaimRepository ticketClaimRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final NamespaceRepository namespaceRepository;
    private final SkillPublishService skillPublishService;
    private final TicketPermissionChecker permissionChecker = new TicketPermissionChecker();

    public TicketService(TicketRepository ticketRepository,
                         TicketClaimRepository ticketClaimRepository,
                         TeamRepository teamRepository,
                         TeamMemberRepository teamMemberRepository,
                         NamespaceRepository namespaceRepository,
                         SkillPublishService skillPublishService) {
        this.ticketRepository = ticketRepository;
        this.ticketClaimRepository = ticketClaimRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.namespaceRepository = namespaceRepository;
        this.skillPublishService = skillPublishService;
    }

    @Transactional
    public Ticket createTicket(String title,
                               String description,
                               TicketMode mode,
                               java.math.BigDecimal reward,
                               String namespaceSlug,
                               Long targetTeamId,
                               String targetUserId,
                               String creatorId,
                               Map<Long, NamespaceRole> userNsRoles,
                               Set<String> platformRoles) {
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
        NamespaceRole namespaceRole = resolveNamespaceRole(namespace.getId(), userNsRoles);
        if (!permissionChecker.canCreate(platformRoles, namespaceRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }

        if (targetTeamId != null) {
            Team team = teamRepository.findById(targetTeamId)
                    .orElseThrow(() -> new DomainBadRequestException("error.team.notFound", targetTeamId));
            if (!team.getNamespaceId().equals(namespace.getId())) {
                throw new DomainBadRequestException("error.ticket.teamNamespaceMismatch", targetTeamId);
            }
        }

        Ticket ticket = new Ticket(title, description, mode, reward, creatorId, namespace.getId(), targetTeamId, targetUserId);
        if (mode == TicketMode.ASSIGN && (targetTeamId != null || targetUserId != null)) {
            ticket.setStatus(TicketStatus.CLAIMED);
        }
        Ticket saved = ticketRepository.save(ticket);
        if (saved.getStatus() == TicketStatus.CLAIMED) {
            ticketClaimRepository.save(new TicketClaim(saved.getId(), targetUserId, targetTeamId, TicketClaimStatus.ACCEPTED));
        }
        return saved;
    }

    @Transactional
    public Ticket claimTicket(Long ticketId,
                              String userId,
                              Long teamId,
                              Map<Long, NamespaceRole> userNsRoles,
                              Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        ensureStatus(ticket, TicketStatus.OPEN);
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        if (!permissionChecker.canClaim(platformRoles, namespaceRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        if (ticket.getTargetTeamId() != null && !ticket.getTargetTeamId().equals(teamId)) {
            throw new DomainForbiddenException("error.ticket.claim.teamMismatch");
        }
        if (ticket.getTargetUserId() != null && !ticket.getTargetUserId().equals(userId)) {
            throw new DomainForbiddenException("error.ticket.claim.userMismatch");
        }
        if (teamId != null && teamMemberRepository.findByTeamIdAndUserId(teamId, userId).isEmpty()) {
            throw new DomainForbiddenException("error.ticket.claim.notTeamMember");
        }

        ticket.setStatus(TicketStatus.CLAIMED);
        Ticket saved = ticketRepository.save(ticket);
        ticketClaimRepository.save(new TicketClaim(saved.getId(), userId, teamId, TicketClaimStatus.ACCEPTED));
        return saved;
    }

    @Transactional
    public Ticket startProgress(Long ticketId,
                                String userId,
                                Map<Long, NamespaceRole> userNsRoles,
                                Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        if (ticket.getStatus() != TicketStatus.CLAIMED && ticket.getStatus() != TicketStatus.FAILED) {
            throw new DomainBadRequestException("error.ticket.status.invalid", ticket.getStatus().name());
        }
        ensureAssigneeOrPermitted(ticket, userId, userNsRoles, platformRoles);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket submitForReview(Long ticketId,
                                  String userId,
                                  Map<Long, NamespaceRole> userNsRoles,
                                  Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        ensureStatus(ticket, TicketStatus.IN_PROGRESS);
        ensureAssigneeOrPermitted(ticket, userId, userNsRoles, platformRoles);
        ticket.setStatus(TicketStatus.TEAM_REVIEW);
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket rejectReview(Long ticketId,
                               String userId,
                               Map<Long, NamespaceRole> userNsRoles,
                               Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        ensureStatus(ticket, TicketStatus.TEAM_REVIEW);
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        TeamRole teamRole = resolveTeamRole(ticket, userId);
        if (!permissionChecker.canReject(platformRoles, namespaceRole, teamRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        ticket.setStatus(TicketStatus.REJECTED);
        return ticketRepository.save(ticket);
    }

    @Transactional
    public SkillPublishService.PublishResult submitSkill(Long ticketId,
                                                         List<PackageEntry> entries,
                                                         SkillVisibility visibility,
                                                         boolean confirmWarnings,
                                                         String userId,
                                                         Map<Long, NamespaceRole> userNsRoles,
                                                         Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        ensureStatus(ticket, TicketStatus.TEAM_REVIEW);
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        TeamRole teamRole = resolveTeamRole(ticket, userId);
        if (!permissionChecker.canSubmitSkill(platformRoles, namespaceRole, teamRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }

        Namespace namespace = namespaceRepository.findById(ticket.getNamespaceId())
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.id.notFound", ticket.getNamespaceId()));
        SkillPublishService.PublishResult publishResult = skillPublishService.publishFromEntries(
                namespace.getSlug(),
                entries,
                userId,
                visibility,
                platformRoles,
                confirmWarnings
        );

        ticket.setStatus(TicketStatus.SUBMITTED);
        ticket.setSubmitSkillId(publishResult.skillId());
        ticket.setSubmitSkillVersionId(publishResult.version().getId());
        ticketRepository.save(ticket);

        return publishResult;
    }

    @Transactional
    public void markSkillPublished(Long skillId, Long versionId) {
        Optional<Ticket> ticket = findBySkillReference(skillId, versionId);
        ticket.ifPresent(found -> {
            if (found.getStatus() == TicketStatus.SUBMITTED) {
                found.setStatus(TicketStatus.DONE);
                ticketRepository.save(found);
            }
        });
    }

    @Transactional
    public void markSkillRejected(Long skillId, Long versionId) {
        Optional<Ticket> ticket = findBySkillReference(skillId, versionId);
        ticket.ifPresent(found -> {
            if (found.getStatus() == TicketStatus.SUBMITTED) {
                found.setStatus(TicketStatus.FAILED);
                ticketRepository.save(found);
            }
        });
    }

    public Ticket getTicketForView(Long ticketId,
                                   String userId,
                                   Map<Long, NamespaceRole> userNsRoles,
                                   Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        if (permissionChecker.canView(platformRoles, namespaceRole)) {
            return ticket;
        }
        if (ticket.getCreatorId().equals(userId) || isAssignee(ticket, userId)) {
            return ticket;
        }
        throw new DomainForbiddenException("error.ticket.noPermission");
    }

    public List<Ticket> listTickets(String namespaceSlug,
                                    String userId,
                                    Map<Long, NamespaceRole> userNsRoles,
                                    Set<String> platformRoles) {
        if (namespaceSlug == null || namespaceSlug.isBlank()) {
            return ticketRepository.findByCreatorId(userId);
        }
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
        NamespaceRole namespaceRole = resolveNamespaceRole(namespace.getId(), userNsRoles);
        if (permissionChecker.canView(platformRoles, namespaceRole)) {
            return ticketRepository.findByNamespaceId(namespace.getId());
        }
        List<Long> allowedIds = new ArrayList<>();
        ticketRepository.findByCreatorId(userId).forEach(ticket -> {
            if (ticket.getNamespaceId().equals(namespace.getId())) {
                allowedIds.add(ticket.getId());
            }
        });
        List<Long> teamIds = teamMemberRepository.findByUserId(userId).stream()
                .map(TeamMember::getTeamId)
                .distinct()
                .toList();
        ticketClaimRepository.findByUserIdAndStatus(userId, TicketClaimStatus.ACCEPTED).forEach(claim -> allowedIds.add(claim.getTicketId()));
        if (!teamIds.isEmpty()) {
            ticketClaimRepository.findByTeamIdInAndStatus(teamIds, TicketClaimStatus.ACCEPTED)
                    .forEach(claim -> allowedIds.add(claim.getTicketId()));
        }
        if (allowedIds.isEmpty()) {
            return List.of();
        }
        return ticketRepository.findByIdIn(allowedIds.stream().distinct().toList());
    }

    private Ticket getTicket(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new DomainBadRequestException("error.ticket.notFound", ticketId));
    }

    private void ensureStatus(Ticket ticket, TicketStatus required) {
        if (ticket.getStatus() != required) {
            throw new DomainBadRequestException("error.ticket.status.invalid", ticket.getStatus().name());
        }
    }

    private NamespaceRole resolveNamespaceRole(Long namespaceId, Map<Long, NamespaceRole> userNsRoles) {
        return userNsRoles != null ? userNsRoles.get(namespaceId) : null;
    }

    private TeamRole resolveTeamRole(Ticket ticket, String userId) {
        Long teamId = resolveClaimedTeam(ticket);
        if (teamId == null) {
            return null;
        }
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .map(TeamMember::getRole)
                .orElse(null);
    }

    private Long resolveClaimedTeam(Ticket ticket) {
        return ticketClaimRepository.findByTicketIdAndStatus(ticket.getId(), TicketClaimStatus.ACCEPTED)
                .map(TicketClaim::getTeamId)
                .orElse(null);
    }

    private void ensureAssigneeOrPermitted(Ticket ticket,
                                           String userId,
                                           Map<Long, NamespaceRole> userNsRoles,
                                           Set<String> platformRoles) {
        if (isAssignee(ticket, userId)) {
            return;
        }
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        TeamRole teamRole = resolveTeamRole(ticket, userId);
        if (!permissionChecker.canDevelop(platformRoles, namespaceRole, teamRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
    }

    private boolean isAssignee(Ticket ticket, String userId) {
        Optional<TicketClaim> claim = ticketClaimRepository.findByTicketIdAndStatus(ticket.getId(), TicketClaimStatus.ACCEPTED);
        if (claim.isEmpty()) {
            return ticket.getCreatorId().equals(userId);
        }
        TicketClaim accepted = claim.get();
        if (userId.equals(accepted.getUserId())) {
            return true;
        }
        if (accepted.getTeamId() == null) {
            return false;
        }
        return teamMemberRepository.findByTeamIdAndUserId(accepted.getTeamId(), userId).isPresent();
    }

    private Optional<Ticket> findBySkillReference(Long skillId, Long versionId) {
        if (versionId != null) {
            Optional<Ticket> byVersion = ticketRepository.findBySubmitSkillVersionId(versionId);
            if (byVersion.isPresent()) {
                return byVersion;
            }
        }
        if (skillId != null) {
            return ticketRepository.findBySubmitSkillId(skillId);
        }
        return Optional.empty();
    }
}
