package com.foremen.service.clientpayment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.ClientPaymentView;
import com.foremen.dao.ClientPaymentDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.proforma.ProformaPdfService;

/**
 * FOR-11-06 (R1.3 - R1.7, R3.4; design 2 and 3): the facade of the unified client payment API. It owns the
 * reads (list, summary, by id, PDF) and the per-kind authorization of the writes; the writes themselves stay in
 * the existing schedule and proforma services, which the controller calls with the detail ids this service
 * resolves from the base id.
 *
 * <p>Rules: a payment outside the caller's projects or not visible to the role answers 404
 * ({@code error.clientPayment.notFound}); a CLIENT never reaches a staff action (403
 * {@code error.access.denied}); FINANCIER writes only the WORKS kind and a role that does not see a kind gets
 * {@code error.clientPayment.kind.forbidden}.
 */
@Service
public class ClientPaymentService {

    public static final String NOT_FOUND_KEY = "error.clientPayment.notFound";
    public static final String KIND_FORBIDDEN_KEY = "error.clientPayment.kind.forbidden";
    private static final String ACCESS_DENIED_KEY = "error.access.denied";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_FINANCIER = "ROLE_FINANCIER";

    private final ClientPaymentDao dao;
    private final ClientProformaLineDao lineDao;
    private final ClientPaymentQueryService queryService;
    private final ClientPaymentAssembler assembler;
    private final ClientPaymentAudienceResolver audienceResolver;
    private final ProcurementProjectAccess projectAccess;
    private final ProformaPdfService pdfService;
    private final Clock clock;

    public ClientPaymentService(ClientPaymentDao dao, ClientProformaLineDao lineDao,
                                ClientPaymentQueryService queryService, ClientPaymentAssembler assembler,
                                ClientPaymentAudienceResolver audienceResolver,
                                ProcurementProjectAccess projectAccess, ProformaPdfService pdfService,
                                Clock clock) {
        this.dao = dao;
        this.lineDao = lineDao;
        this.queryService = queryService;
        this.assembler = assembler;
        this.audienceResolver = audienceResolver;
        this.projectAccess = projectAccess;
        this.pdfService = pdfService;
        this.clock = clock;
    }

    /** A stored PDF with its download file name. */
    public record StoredPdf(String fileName, byte[] bytes) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Page<ClientPaymentView> find(Pageable pageable, ClientPaymentFilter filter) {
        ClientPaymentVisibility visibility = audienceResolver.visibility();
        LocalDate today = LocalDate.now(clock);
        Page<ClientPaymentEntity> page = queryService.find(pageable, filter);
        List<ClientPaymentView> views = page.getContent().stream()
                .map(p -> assembler.assemble(p, visibility, today, linesOf(p)))
                .flatMap(Optional::stream)
                .toList();
        return new PageImpl<>(views, page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ClientPaymentSummary summary(ClientPaymentFilter filter) {
        return queryService.summary(filter);
    }

    @Transactional(readOnly = true)
    public ClientPaymentView get(Long id) {
        ClientPaymentEntity p = loadVisible(id);
        return assembler.assemble(p, audienceResolver.visibility(), LocalDate.now(clock), linesOf(p))
                .orElseThrow(() -> notFound(id));
    }

    /** The stored PDF of an issued MATERIALS payment; a WORKS payment has none (404). */
    @Transactional(readOnly = true)
    public StoredPdf pdf(Long id) {
        ClientPaymentEntity p = loadVisible(id);
        ClientProformaEntity proforma = p.getProforma();
        if (p.getKind() != ClientPaymentKind.MATERIALS || proforma == null) {
            throw notFound(id);
        }
        return new StoredPdf(ProformaPdfService.fileName(proforma.getNumber()), pdfService.read(proforma));
    }

    // ------------------------------------------------------------------ id resolution for the writes

    /**    /**    /**    /**    /**    /**    /**    /ler may change (staff, not FINANCIER). */
    @Transactional(readOnly = true)
    public Long materialsProformaIdForWrite(Long id) {
        requireStaff();
        ClientPaymentEntity p = loadVisible(id);
        if (p.getKind() != ClientPaymentKind.MATERIALS || p.getProforma() == null) {
            throw kindForbidden();
        }
        requireMaterialsWrite();
        return p.getProforma().getId();
    }

    /** The tranche id of a WORKS payment the caller may change. */
    @Transactional(readOnly = true)
    public TrancheRef worksTrancheForWrite(Long id) {
        requireStaff();
        ClientPaymentEntity p = loadVisible(id);
        if (p.getKind() != ClientPaymentKind.WORKS || p.getTranche() == null
                || p.getTranche().getSchedule() == null) {
            throw kindForbidden();
        }
        requireWorks();
        return new TrancheRef(p.getTranche().getId(), p.getTranche().getSchedule().getId());
    }

    /** The kind of a payment the caller can see (404 otherwise); used to dispatch update and delete. */
    @Transactional(readOnly = true)
    public ClientPaymentKind kindOf(Long id) {
        requireStaff();
        return loadVisible(id).getKind();
    }

    /** Tranche and schedule ids of a WORKS payment. */
    public record TrancheRef(Long trancheId, Long scheduleId) {
    }

    // ------------------------------------------------------------------ per-kind authorization

    /** A CLIENT (or an unauthenticated caller) never reaches a staff action: 403. */
    public void requireStaff() {
        if (audienceResolver.visibility() == ClientPaymentVisibility.CLIENT || currentAuth() == null
                || audienceResolver.visibility() == ClientPaymentVisibility.NONE) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED_KEY);
        }
    }

    /** Schedule, tranche and transaction endpoints: staff that sees the WORKS kind. */
    public void requireWorks() {
        requireStaff();
        if (!audienceResolver.visibility().allows(ClientPaymentKind.WORKS)) {
            throw kindForbidden();
        }
    }

    /** Proforma writes: staff that sees MATERIALS and is not a FINANCIER (read only on that kind). */
    public void requireMaterialsWrite() {
        requireStaff();
        if (!audienceResolver.visibility().allows(ClientPaymentKind.MATERIALS) || hasAuthority(ROLE_FINANCIER)) {
            throw kindForbidden();
        }
    }

    // ------------------------------------------------------------------ internals

    private ClientPaymentEntity loadVisible(Long id) {
        ClientPaymentEntity p = id == null ? null : dao.findById(id).orElse(null);
        if (p == null || p.getProject() == null) {
            throw notFound(id);
        }
        if (!hasAuthority(ROLE_ADMIN)) {
            Set<Long> allowed = projectAccess.allowedProjectIds(userId());
            if (allowed == null || !allowed.contains(p.getProject().getId())) {
                throw notFound(id);
            }
        }
        if (!ClientPaymentAssembler.isVisible(p, audienceResolver.visibility())) {
            throw notFound(id);
        }
        return p;
    }

    private List<ClientProformaLineEntity> linesOf(ClientPaymentEntity p) {
        if (p.getKind() != ClientPaymentKind.MATERIALS || p.getProforma() == null) {
            return List.of();
        }
        return lineDao.findByProformaIdOrderByLineNo(p.getProforma().getId());
    }

    private static Authentication currentAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth : null;
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = currentAuth();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (authority.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static Long userId() {
        Authentication auth = currentAuth();
        if (auth == null) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_KEY, id);
    }

    private static ForemenApiException kindForbidden() {
        return new ForemenApiException(HttpStatus.FORBIDDEN, KIND_FORBIDDEN_KEY);
    }
}
