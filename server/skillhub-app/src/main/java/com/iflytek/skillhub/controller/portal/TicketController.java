package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.ticket.Ticket;
import com.iflytek.skillhub.domain.ticket.TicketMode;
import com.iflytek.skillhub.domain.ticket.TicketService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.dto.TicketClaimRequest;
import com.iflytek.skillhub.dto.TicketCreateRequest;
import com.iflytek.skillhub.dto.TicketRejectRequest;
import com.iflytek.skillhub.dto.TicketResponse;
import com.iflytek.skillhub.dto.TicketSubmitSkillResponse;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api/v1/tickets", "/api/web/tickets"})
public class TicketController extends BaseApiController {

    private final TicketService ticketService;
    private final SkillPackageArchiveExtractor skillPackageArchiveExtractor;
    private final SkillHubMetrics skillHubMetrics;
    private final NamespaceRepository namespaceRepository;

    public TicketController(TicketService ticketService,
                            SkillPackageArchiveExtractor skillPackageArchiveExtractor,
                            ApiResponseFactory responseFactory,
                            SkillHubMetrics skillHubMetrics,
                            NamespaceRepository namespaceRepository) {
        super(responseFactory);
        this.ticketService = ticketService;
        this.skillPackageArchiveExtractor = skillPackageArchiveExtractor;
        this.skillHubMetrics = skillHubMetrics;
        this.namespaceRepository = namespaceRepository;
    }

    @PostMapping
    public ApiResponse<TicketResponse> createTicket(
            @Valid @RequestBody TicketCreateRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        TicketMode mode = parseMode(request.mode());
        Ticket ticket = ticketService.createTicket(
                request.title(),
                request.description(),
                mode,
                request.reward(),
                request.namespace(),
                principal.userId(),
                userNsRoles,
                principal.platformRoles()
        );
        return ok("response.success.created", toResponse(ticket));
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<TicketResponse> getTicket(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Ticket ticket = ticketService.getTicketForView(ticketId, principal.userId(), userNsRoles, principal.platformRoles());
        return ok("response.success.read", toResponse(ticket));
    }

    @GetMapping
    public ApiResponse<List<TicketResponse>> listTickets(
            @RequestParam(value = "namespace", required = false) String namespace,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        List<TicketResponse> tickets = ticketService.listTickets(namespace, principal.userId(), userNsRoles, principal.platformRoles())
                .stream()
                .map(this::toResponse)
                .toList();
        return ok("response.success.read", tickets);
    }

    @PostMapping("/{ticketId}/claim")
    public ApiResponse<TicketResponse> claimTicket(
            @PathVariable Long ticketId,
            @RequestBody(required = false) TicketClaimRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Long teamId = request != null ? request.teamId() : null;
        Ticket ticket = ticketService.claimTicket(ticketId, principal.userId(), teamId, userNsRoles, principal.platformRoles());
        return ok("response.success.updated", toResponse(ticket));
    }

    @PostMapping("/{ticketId}/start")
    public ApiResponse<TicketResponse> startTicket(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Ticket ticket = ticketService.startProgress(ticketId, principal.userId(), userNsRoles, principal.platformRoles());
        return ok("response.success.updated", toResponse(ticket));
    }

    @PostMapping("/{ticketId}/review")
    public ApiResponse<TicketResponse> submitForReview(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Ticket ticket = ticketService.completeReview(ticketId, principal.userId(), userNsRoles, principal.platformRoles());
        return ok("response.success.updated", toResponse(ticket));
    }

    @PostMapping("/{ticketId}/reject")
    public ApiResponse<TicketResponse> rejectReview(
            @PathVariable Long ticketId,
            @Valid @RequestBody(required = false) TicketRejectRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        Ticket ticket = ticketService.rejectReview(ticketId, principal.userId(), userNsRoles, principal.platformRoles());
        return ok("response.success.updated", toResponse(ticket));
    }

    @PostMapping("/{ticketId}/submit-skill")
    public ApiResponse<TicketSubmitSkillResponse> submitSkill(
            @PathVariable Long ticketId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("visibility") String visibility,
            @RequestParam(value = "confirmWarnings", defaultValue = "false") boolean confirmWarnings,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) throws IOException {
        SkillVisibility skillVisibility = SkillVisibility.valueOf(visibility.toUpperCase());

        List<PackageEntry> entries;
        try {
            entries = skillPackageArchiveExtractor.extract(file);
        } catch (IllegalArgumentException e) {
            throw new DomainBadRequestException("error.skill.publish.package.invalid", e.getMessage());
        }

        SkillPublishService.PublishResult publishResult = ticketService.submitSkill(
                ticketId,
                entries,
                skillVisibility,
                confirmWarnings,
                principal.userId(),
                userNsRoles,
                principal.platformRoles()
        );

        Ticket updatedTicket = ticketService.getTicketForView(ticketId, principal.userId(), userNsRoles, principal.platformRoles());
        String namespaceSlug = namespaceRepository.findById(updatedTicket.getNamespaceId())
                .map(namespace -> namespace.getSlug())
                .orElse(null);
        PublishResponse publishResponse = new PublishResponse(
                publishResult.skillId(),
                namespaceSlug,
                publishResult.slug(),
                publishResult.version().getVersion(),
                publishResult.version().getStatus().name(),
                publishResult.version().getFileCount(),
                publishResult.version().getTotalSize()
        );
        if (namespaceSlug != null) {
            skillHubMetrics.incrementSkillPublish(namespaceSlug, publishResult.version().getStatus().name());
        }

        return ok("response.success.published", new TicketSubmitSkillResponse(ticketId, publishResponse));
    }

    @DeleteMapping("/{ticketId}")
    public ApiResponse<Void> cancelTicket(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        ticketService.cancelTicket(ticketId, principal.userId(), userNsRoles, principal.platformRoles());
        return ok("response.success.deleted", null);
    }

    private TicketMode parseMode(String mode) {
        try {
            return TicketMode.valueOf(mode.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new DomainBadRequestException("error.ticket.mode.invalid", mode);
        }
    }

    private TicketResponse toResponse(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getMode().name(),
                ticket.getReward(),
                ticket.getStatus().name(),
                ticket.getCreatorId(),
                ticket.getNamespaceId(),
                ticket.getSubmitSkillId(),
                ticket.getSubmitSkillVersionId(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt()
        );
    }
}
