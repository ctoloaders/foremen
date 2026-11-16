package com.foremen.service.procurement.proforma;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EffectiveBankAccountResolver;
import com.foremen.service.document.CompanyProfileDto;
import com.foremen.service.document.CompanyProfileService;
import com.foremen.service.document.pdf.InMemoryMultipartFile;
import com.foremen.service.document.pdf.PdfRenderer;
import com.foremen.service.image.ImageStorage;

/**
 * FOR-11-03 (R4.4, R4.5, R4.7): renders the proforma PDF and keeps it in object storage.
 *
 * <p>Storage goes through the {@link ImageStorage#storeObject} seam that {@code PdfFreezeService}
 * uses; no new storage client. The key is returned to the caller, which owns the transaction and the
 * compensation when a later step fails.
 *
 * <p>Data sources: seller from {@link CompanyProfileService} (name, address, NIP, bank account, the
 * last one added to the profile by changeset 240); buyer from the project's first ACTIVE {@code CLIENT}
 * member (name, NIP) with the project address, falling back to the project name.
 */
@Service
public class ProformaPdfService {

    private static final Logger log = LoggerFactory.getLogger(ProformaPdfService.class);

    static final String COMPANY_INCOMPLETE = "error.proforma.companyProfile.incomplete";
    static final String PDF_FAILED = "error.proforma.pdf.failed";

    /** Object-key namespace of stored proforma PDFs. */
    static final String STORAGE_NAMESPACE = "client-proformas";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String CLIENT_ROLE = "CLIENT";

    private final ProformaHtmlBuilder htmlBuilder;
    private final PdfRenderer pdfRenderer;
    private final ImageStorage objectStorage;
    private final CompanyProfileService companyProfileService;
    private final ProjectMemberDao projectMemberDao;
    private final EffectiveBankAccountResolver bankAccountResolver;

    public ProformaPdfService(ProformaHtmlBuilder htmlBuilder,
                              PdfRenderer pdfRenderer,
                              ImageStorage objectStorage,
                              CompanyProfileService companyProfileService,
                              ProjectMemberDao projectMemberDao,
                              EffectiveBankAccountResolver bankAccountResolver) {
        this.htmlBuilder = htmlBuilder;
        this.pdfRenderer = pdfRenderer;
        this.objectStorage = objectStorage;
        this.companyProfileService = companyProfileService;
        this.projectMemberDao = projectMemberDao;
        this.bankAccountResolver = bankAccountResolver;
    }

    /**
     * Renders and stores the PDF of an issued proforma.
     *
     * @param proforma the proforma with number, dates and totals already set
     * @param lines    the frozen lines of that proforma
     * @param language {@code pl} or {@code ru}
     * @return the object key of the stored PDF
     * @throws ForemenApiException 409 {@code companyProfile.incomplete} when the seller data or bank
     *                             account is missing; 502 {@code pdf.failed} when rendering or storing fails
     */
    public String render(ClientProformaEntity proforma, List<ClientProformaLineEntity> lines, String language) {
        ProformaDocModel model = buildModel(proforma, lines);
        ProformaPdfLabels labels = ProformaPdfLabels.forLanguage(language);
        try {
            byte[] pdf = pdfRenderer.renderToPdf(htmlBuilder.build(model, labels));
            InMemoryMultipartFile upload = new InMemoryMultipartFile(
                    "file", fileName(proforma.getNumber()), PDF_CONTENT_TYPE, pdf);
            String key = objectStorage.storeObject(upload, STORAGE_NAMESPACE);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("object storage returned no key");
            }
            return key;
        } catch (RuntimeException e) {
            log.warn("Proforma {} PDF failed: {}", proforma.getNumber(), e.toString());
            throw new ForemenApiException(HttpStatus.BAD_GATEWAY, PDF_FAILED);
        }
    }

    /** The stored bytes of an issued proforma's PDF (R4.7: streamed as stored, never regenerated). */
    public byte[] read(ClientProformaEntity proforma) {
        String key = proforma.getPdfObjectKey();
        if (key == null || key.isBlank()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", proforma.getId());
        }
        try {
            byte[] bytes = objectStorage.readObject(key);
            if (bytes == null) {
                throw new IllegalStateException("object storage returned no bytes");
            }
            return bytes;
        } catch (RuntimeException e) {
            log.warn("Proforma {} PDF read failed: {}", proforma.getNumber(), e.toString());
            throw new ForemenApiException(HttpStatus.BAD_GATEWAY, PDF_FAILED);
        }
    }

    /** {@code PF/2026/000123} gives {@code PF-2026-000123.pdf} (R4.7). */
    public static String fileName(String number) {
        String base = number == null ? "proforma" : number.replace('/', '-');
        return base + ".pdf";
    }

    ProformaDocModel buildModel(ClientProformaEntity proforma, List<ClientProformaLineEntity> lines) {
        CompanyProfileDto company = companyProfileService.get();
        ProjectEntity project = proforma.getProject();
        // R4.6: an issued payment renders its snapshot; a preview uses the effective account.
        String snapshot = proforma.getPayment() == null ? null : proforma.getPayment().getBankAccountSnapshot();
        String bankAccount = !isBlank(snapshot) ? snapshot : bankAccountResolver.forProject(project);
        // R4.7: company name, address and NIP are required; the account may come from the project.
        if (isBlank(company.fullName()) || isBlank(company.registeredAddress()) || isBlank(company.nip())
                || isBlank(bankAccount)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, COMPANY_INCOMPLETE);
        }

        String buyerName = project.getName();
        String buyerNip = null;
        UserEntity client = primaryClient(project.getId());
        if (client != null && !isBlank(client.getName())) {
            buyerName = client.getName();
            buyerNip = client.getNip();
        }
        String buyerAddress = !isBlank(project.getFormattedAddress())
                ? project.getFormattedAddress() : project.getAddress();

        List<ProformaDocModel.Line> docLines = new ArrayList<>(lines.size());
        for (ClientProformaLineEntity l : lines) {
            docLines.add(new ProformaDocModel.Line(l.getLineNo(), l.getDescription(), l.getSku(),
                    l.getQuantity(), l.getUnitName(), l.getUnitSaleNet(), l.getVatRatePercent(),
                    l.getNet(), l.getVat(), l.getGross()));
        }
        return new ProformaDocModel(proforma.getNumber(), proforma.getIssueDate(), proforma.getDueDate(),
                proforma.getCurrency(), company.fullName(), company.registeredAddress(), company.nip(),
                bankAccount, buyerName, buyerAddress, buyerNip, docLines,
                proforma.getTotalNet(), proforma.getTotalVat(), proforma.getTotalGross(), proforma.getNote());
    }

    private UserEntity primaryClient(Long projectId) {
        for (ProjectMemberEntity m : projectMemberDao.findByProjectIdWithUserAndRole(projectId)) {
            if (m.getProjectRole() != null && CLIENT_ROLE.equals(m.getProjectRole().getCode())
                    && m.getAssignmentStatus() == AssignmentStatus.ACTIVE) {
                return m.getUser();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
