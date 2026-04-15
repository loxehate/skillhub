package com.iflytek.skillhub.domain.ticket;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.event.TicketClaimedEvent;
import com.iflytek.skillhub.domain.event.TicketClosedEvent;
import com.iflytek.skillhub.domain.event.TicketCreatedEvent;
import com.iflytek.skillhub.domain.event.TicketRejectedEvent;
import com.iflytek.skillhub.domain.event.TicketReviewSubmittedEvent;
import com.iflytek.skillhub.domain.event.TicketSkillSubmittedEvent;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.service.SkillHardDeleteService;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketClaimRepository ticketClaimRepository;
    private final TicketCommentRepository ticketCommentRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final NamespaceRepository namespaceRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final SkillRepository skillRepository;
    private final SkillHardDeleteService skillHardDeleteService;
    private final SkillPublishService skillPublishService;
    private final ApplicationEventPublisher eventPublisher;
    private final TicketPermissionChecker permissionChecker = new TicketPermissionChecker();

    public TicketService(TicketRepository ticketRepository,
                         TicketClaimRepository ticketClaimRepository,
                         TicketCommentRepository ticketCommentRepository,
                         TeamRepository teamRepository,
                         TeamMemberRepository teamMemberRepository,
                         NamespaceRepository namespaceRepository,
                         ReviewTaskRepository reviewTaskRepository,
                         SkillRepository skillRepository,
                         SkillHardDeleteService skillHardDeleteService,
                         SkillPublishService skillPublishService,
                         ApplicationEventPublisher eventPublisher) {
        this.ticketRepository = ticketRepository;
        this.ticketClaimRepository = ticketClaimRepository;
        this.ticketCommentRepository = ticketCommentRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.namespaceRepository = namespaceRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.skillRepository = skillRepository;
        this.skillHardDeleteService = skillHardDeleteService;
        this.skillPublishService = skillPublishService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Ticket createTicket(String title,
                               String description,
                               TicketMode mode,
                               java.math.BigDecimal reward,
                               String namespaceSlug,
                               Long targetTeamId,
                               String creatorId,
                               Map<Long, NamespaceRole> userNsRoles,
                               Set<String> platformRoles) {
        String resolvedNamespaceSlug = (namespaceSlug == null || namespaceSlug.isBlank()) ? "global" : namespaceSlug.trim();
        Namespace namespace = resolveNamespace(resolvedNamespaceSlug);
        NamespaceRole namespaceRole = resolveNamespaceRole(namespace.getId(), userNsRoles);
        if (!permissionChecker.canCreate(platformRoles, namespaceRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        validateReward(reward);

        Ticket ticket = new Ticket(title, description, mode, reward, creatorId, namespace.getId(), null, null);
        Ticket saved = ticketRepository.save(ticket);
        eventPublisher.publishEvent(new TicketCreatedEvent(
                saved.getId(),
                saved.getTitle(),
                saved.getNamespaceId(),
                creatorId,
                saved.getMode().name(),
                null
        ));
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
        eventPublisher.publishEvent(new TicketClaimedEvent(
                saved.getId(),
                saved.getTitle(),
                saved.getNamespaceId(),
                saved.getCreatorId(),
                userId,
                teamId
        ));
        return saved;
    }

    @Transactional
    public Ticket updateTicket(Long ticketId,
                               String title,
                               String description,
                               TicketMode mode,
                               java.math.BigDecimal reward,
                               String namespaceSlug,
                               Long targetTeamId,
                               String userId,
                               Map<Long, NamespaceRole> userNsRoles,
                               Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        ensureStatus(ticket, TicketStatus.OPEN);
        NamespaceRole currentNamespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        boolean canManage = permissionChecker.canManage(platformRoles, currentNamespaceRole);
        if (!ticket.getCreatorId().equals(userId) && !canManage) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }

        String resolvedNamespaceSlug = (namespaceSlug == null || namespaceSlug.isBlank()) ? "global" : namespaceSlug.trim();
        Namespace namespace = resolveNamespace(resolvedNamespaceSlug);
        NamespaceRole targetNamespaceRole = resolveNamespaceRole(namespace.getId(), userNsRoles);
        if (!permissionChecker.canCreate(platformRoles, targetNamespaceRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        validateReward(reward);

        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setMode(mode);
        ticket.setReward(reward);
        ticket.setNamespaceId(namespace.getId());
        ticket.setTargetTeamId(null);
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket startProgress(Long ticketId,
                                String userId,
                                Map<Long, NamespaceRole> userNsRoles,
                                Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        if (ticket.getStatus() != TicketStatus.CLAIMED
                && ticket.getStatus() != TicketStatus.FAILED
                && ticket.getStatus() != TicketStatus.REJECTED) {
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
        Ticket saved = ticketRepository.save(ticket);
        eventPublisher.publishEvent(new TicketReviewSubmittedEvent(
                saved.getId(),
                saved.getTitle(),
                saved.getNamespaceId(),
                saved.getCreatorId(),
                userId
        ));
        return saved;
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
        if (ticket.getSubmitSkillId() != null) {
            skillRepository.findById(ticket.getSubmitSkillId()).ifPresent(skill -> {
                String namespaceSlug = namespaceRepository.findById(skill.getNamespaceId())
                        .map(Namespace::getSlug)
                        .orElse("global");
                skillHardDeleteService.hardDeleteSkill(skill, namespaceSlug, userId, null, null);
            });
            ticket.setSubmitSkillId(null);
            ticket.setSubmitSkillVersionId(null);
        }
        ticket.setStatus(TicketStatus.CLAIMED);
        Ticket saved = ticketRepository.save(ticket);
        eventPublisher.publishEvent(new TicketRejectedEvent(
                saved.getId(),
                saved.getTitle(),
                saved.getNamespaceId(),
                saved.getCreatorId(),
                userId
        ));
        return saved;
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
        ensureStatus(ticket, TicketStatus.IN_PROGRESS);
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
        Ticket saved = ticketRepository.save(ticket);
        eventPublisher.publishEvent(new TicketSkillSubmittedEvent(
                saved.getId(),
                saved.getTitle(),
                saved.getNamespaceId(),
                saved.getCreatorId(),
                userId,
                publishResult.skillId(),
                publishResult.version().getId()
        ));

        return publishResult;
    }

    @Transactional
    public void markSkillPublished(Long skillId, Long versionId) {
        Optional<Ticket> ticket = findBySkillReference(skillId, versionId);
        ticket.ifPresent(found -> {
            if (found.getStatus() == TicketStatus.SUBMITTED) {
                found.setStatus(TicketStatus.TEAM_REVIEW);
                Ticket saved = ticketRepository.save(found);
                eventPublisher.publishEvent(new TicketReviewSubmittedEvent(
                        saved.getId(),
                        saved.getTitle(),
                        saved.getNamespaceId(),
                        saved.getCreatorId(),
                        saved.getCreatorId()
                ));
            }
        });
    }

    @Transactional
    public void markSkillRejected(Long skillId, Long versionId) {
        Optional<Ticket> ticket = findBySkillReference(skillId, versionId);
        ticket.ifPresent(found -> {
            if (found.getStatus() == TicketStatus.SUBMITTED) {
                found.setStatus(TicketStatus.FAILED);
                Ticket saved = ticketRepository.save(found);
                eventPublisher.publishEvent(new TicketClosedEvent(
                        saved.getId(),
                        saved.getTitle(),
                        saved.getNamespaceId(),
                        saved.getCreatorId(),
                        null,
                        saved.getStatus().name(),
                        skillId,
                        versionId
                ));
            }
        });
    }

    @Transactional
    public Ticket completeReview(Long ticketId,
                                 String userId,
                                 Map<Long, NamespaceRole> userNsRoles,
                                 Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        ensureStatus(ticket, TicketStatus.TEAM_REVIEW);
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        TeamRole teamRole = resolveTeamRole(ticket, userId);
        if (!permissionChecker.canReview(platformRoles, namespaceRole, teamRole)) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        ticket.setStatus(TicketStatus.DONE);
        Ticket saved = ticketRepository.save(ticket);
        String claimerId = ticketClaimRepository.findByTicketIdAndStatus(saved.getId(), TicketClaimStatus.ACCEPTED)
                .map(TicketClaim::getUserId)
                .orElse(null);
        eventPublisher.publishEvent(new TicketClosedEvent(
                saved.getId(),
                saved.getTitle(),
                saved.getNamespaceId(),
                saved.getCreatorId(),
                claimerId,
                saved.getStatus().name(),
                saved.getSubmitSkillId(),
                saved.getSubmitSkillVersionId()
        ));
        return saved;
    }

    public Ticket getTicketForView(Long ticketId,
                                   String userId,
                                   Map<Long, NamespaceRole> userNsRoles,
                                   Set<String> platformRoles) {
        Ticket ticket = getTicket(ticketId);
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        if (permissionChecker.canView(platformRoles, namespaceRole) || namespaceRole != null) {
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
        Map<Long, NamespaceRole> namespaceRoles = userNsRoles != null ? userNsRoles : Map.of();
        if (namespaceSlug == null || namespaceSlug.isBlank()) {
            if (platformRoles != null && (platformRoles.contains("SUPER_ADMIN")
                    || platformRoles.contains("SKILL_ADMIN")
                    || platformRoles.contains("USER_ADMIN")
                    || platformRoles.contains("AUDITOR"))) {
                return ticketRepository.findAll();
            }
            LinkedHashSet<Long> visibleIds = new LinkedHashSet<>();
            if (!namespaceRoles.isEmpty()) {
                ticketRepository.findByNamespaceIdIn(namespaceRoles.keySet().stream().toList())
                        .forEach(ticket -> visibleIds.add(ticket.getId()));
            }
            ticketRepository.findByCreatorId(userId).forEach(ticket -> visibleIds.add(ticket.getId()));
            collectClaimedTicketIds(userId, visibleIds);
            if (visibleIds.isEmpty()) {
                return List.of();
            }
            return ticketRepository.findByIdIn(List.copyOf(visibleIds));
        }
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
        NamespaceRole namespaceRole = resolveNamespaceRole(namespace.getId(), userNsRoles);
        if (permissionChecker.canView(platformRoles, namespaceRole) || namespaceRole != null) {
            return ticketRepository.findByNamespaceId(namespace.getId());
        }
        List<Long> allowedIds = new ArrayList<>();
        ticketRepository.findByCreatorId(userId).forEach(ticket -> {
            if (ticket.getNamespaceId().equals(namespace.getId())) {
                allowedIds.add(ticket.getId());
            }
        });
        collectClaimedTicketIds(userId, allowedIds);
        if (allowedIds.isEmpty()) {
            return List.of();
        }
        return ticketRepository.findByIdIn(allowedIds.stream().distinct().toList());
    }

    @Transactional
    public void cancelTicket(Long ticketId,
                             String userId,
                             Map<Long, NamespaceRole> userNsRoles,
                             Set<String> platformRoles) {
        Optional<Ticket> existingTicket = ticketRepository.findById(ticketId);
        if (existingTicket.isEmpty()) {
            return;
        }
        Ticket ticket = existingTicket.get();
        NamespaceRole namespaceRole = resolveNamespaceRole(ticket.getNamespaceId(), userNsRoles);
        boolean canManage = permissionChecker.canManage(platformRoles, namespaceRole);
        boolean isCreator = ticket.getCreatorId().equals(userId);
        if (!isCreator && !canManage) {
            throw new DomainForbiddenException("error.ticket.noPermission");
        }
        ensureStatus(ticket, TicketStatus.OPEN);
        ticketRepository.delete(ticket);
    }

    @Transactional
    public TicketComment addComment(Long ticketId,
                                    String content,
                                    String userId,
                                    Map<Long, NamespaceRole> userNsRoles,
                                    Set<String> platformRoles) {
        Ticket ticket = getTicketForView(ticketId, userId, userNsRoles, platformRoles);
        TicketComment comment = new TicketComment(ticket.getId(), userId, content.trim());
        return ticketCommentRepository.save(comment);
    }

    public List<TicketComment> listComments(Long ticketId,
                                            String userId,
                                            Map<Long, NamespaceRole> userNsRoles,
                                            Set<String> platformRoles) {
        Ticket ticket = getTicketForView(ticketId, userId, userNsRoles, platformRoles);
        return ticketCommentRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
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

    private void collectClaimedTicketIds(String userId, java.util.Collection<Long> ticketIds) {
        List<Long> teamIds = teamMemberRepository.findByUserId(userId).stream()
                .map(TeamMember::getTeamId)
                .distinct()
                .toList();
        ticketClaimRepository.findByUserIdAndStatus(userId, TicketClaimStatus.ACCEPTED)
                .forEach(claim -> ticketIds.add(claim.getTicketId()));
        if (!teamIds.isEmpty()) {
            ticketClaimRepository.findByTeamIdInAndStatus(teamIds, TicketClaimStatus.ACCEPTED)
                    .forEach(claim -> ticketIds.add(claim.getTicketId()));
        }
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

    public Long findPendingReviewTaskId(Long skillVersionId) {
        if (skillVersionId == null) {
            return null;
        }
        return reviewTaskRepository.findBySkillVersionIdAndStatus(skillVersionId, ReviewTaskStatus.PENDING)
                .map(task -> task.getId())
                .orElse(null);
    }

    private Namespace resolveNamespace(String namespaceSlug) {
        return namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
    }

    private void validateReward(java.math.BigDecimal reward) {
        if (reward == null) {
            return;
        }
        if (reward.scale() > 0 && reward.stripTrailingZeros().scale() > 0) {
            throw new DomainBadRequestException("error.ticket.reward.integerOnly");
        }
    }
}
