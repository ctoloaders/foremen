package com.foremen.service.procurement.proforma;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.client.ClientProformaClientDto;
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.PurchaseRequestOverdue;

/**
 * FOR-11-03 (R6.1, R6.2): the client reads of proformas. A caller who is not an ACTIVE {@code CLIENT}
 * member of the proforma's project, and any id that does not exist or is a draft, gets the same 404 so
 * existence never leaks.
 */
@Service
public class ClientProformaClientService {

    static final String CLIENT_ROLE_CODE = "CLIENT";

    private final ClientProformaDao dao;
    private final ClientProformaLineDao lineDao;
    private final ProjectMemberDao projectMemberDao;
    private final ClientProformaClientAssembler assembler;
    private final ProformaPdfService pdfService;
    private final Clock clock = Clock.system(PurchaseRequestOverdue.ZONE);

    public ClientProformaClientService(ClientProformaDao dao, ClientProformaLineDao lineDao,
                                       ProjectMemberDao projectMemberDao,
                                       ClientProformaClientAssembler assembler, ProformaPdfService pdfService) {
        this.dao = dao;
        this.lineDao = lineDao;
        this.projectMemberDao = projectMemberDao;
        this.assembler = assembler;
        this.pdfService = pdfService;
    }

    @Transactional(readOnly = true)
    public List<ClientProformaClientDto> list(Long projectId, Long userId) {
        if (!isActiveClientMember(userId, projectId)) {
            throw notFound(projectId);
        }
        LocalDate today = LocalDate.now(clock);
        return dao.findByProjectIdAndStatusIn(projectId, ClientProformaClientAssembler.CLIENT_VISIBLE).stream()
                .map(p -> assembler.assemble(p, lineDao.findByProformaIdOrderByLineNo(p.getId()), today))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientProformaClientDto get(Long id, Long userId) {
        ClientProformaEntity p = visible(id, userId);
        return assembler.assemble(p, lineDao.findByProformaIdOrderByLineNo(id), LocalDate.now(clock));
    }

    /** The stored PDF bytes, with the file name derived from the proforma number. */
    @Transactional(readOnly = true)
    public StoredPdf pdf(Long id, Long userId) {
        ClientProformaEntity p = visible(id, userId);
        return new StoredPdf(ProformaPdfService.fileName(p.getNumber()), pdfService.read(p));
    }

    public record StoredPdf(String fileName, byte[] bytes) {
    }

    private ClientProformaEntity visible(Long id, Long userId) {
        ClientProformaEntity p = dao.findById(id).orElseThrow(() -> notFound(id));
        if (!ClientProformaClientAssembler.CLIENT_VISIBLE.contains(p.getStatus())
                || !isActiveClientMember(userId, p.getProject().getId())) {
            throw notFound(id);
        }
        return p;
    }

    private boolean isActiveClientMember(Long userId, Long projectId) {
        if (userId == null || projectId == null) {
            return false;
        }
        return projectMemberDao.findByUserIdAndProjectId(userId, projectId)
                .filter(m -> m.getAssignmentStatus() == AssignmentStatus.ACTIVE)
                .map(ProjectMemberEntity::getProjectRole)
                .filter(r -> r != null && CLIENT_ROLE_CODE.equals(r.getCode()))
                .isPresent();
    }

    private static ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ClientProformaService.ENTITY_NOT_FOUND, id);
    }
}
