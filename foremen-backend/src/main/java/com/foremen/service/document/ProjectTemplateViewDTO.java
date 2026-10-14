package com.foremen.service.document;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FOR-05-08 (template placeholder values): the per-project snapshot of every value a document
 * template's {@code {Token}} placeholders can be filled from, served by
 * {@code GET /api/projects/{projectId}/template-view}.
 *
 * <p>It is produced by {@code ProjectTemplateViewService} by reusing the SAME merge resolver engine
 * that fills a real generated document, so the preview can never drift from generation (one source
 * of truth). The nested groups mirror the resolver groups (client / executor requisites / property /
 * schedule / offer); the flat {@link #tokens} map is the authoritative, direct {@code tokenName →
 * value} lookup the editor/preview substitutes with. A value is {@code null}/absent exactly when the
 * backing datum is missing (so the UI shows it as an un-filled placeholder rather than empty text).
 *
 * <p><b>Confidentiality.</b> Only {@link Offer#totalNet} (the approved offer NET total, the single
 * permitted monetary token {@code {TotalBeforeTax}}) is exposed — no estimate / margin / cost data.
 *
 * @param projectId   the project this view was built for
 * @param client      the primary CLIENT member's contact values ({@code {Contact*}})
 * @param executor    the executor company requisites ({@code {UfCrm1663076108Requisite*}}) and the
 *                    executor representative ({@code {Assigned*}} / {@code {UfCrm1692271880*}})
 * @param property    the works address + usable area ({@code {UfCrm1662541716442}} / {@code {UfCrm1691765118573}})
 * @param schedule    the project start / end dates ({@code {Begindate}} / {@code {Closedate}})
 * @param offer       the approved-offer net total ({@code {TotalBeforeTax}})
 * @param tokens      the flat, authoritative {@code tokenName → resolved value} map (resolved tokens
 *                    only; an unresolved token is omitted). This is what the editor substitutes with.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectTemplateViewDTO(
        Long projectId,
        Client client,
        Executor executor,
        Property property,
        Schedule schedule,
        Offer offer,
        Map<String, String> tokens) {

    /**
     * The primary CLIENT member's contact values ({@code ClientResolver}).
     *
     * @param name    {@code {ContactName}} — the client's full name
     * @param lastName {@code {ContactLastName}} — the surname part of the name, if any
     * @param email   {@code {ContactEmail}}
     * @param address {@code {ContactAddress}} / {@code {RequisitePrimaryAddressText}} — the project address
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Client(String name, String lastName, String email, String address) {
    }

    /**
     * The executor ({@code Wykonawca}) — company requisites + the project representative.
     *
     * @param companyName          {@code {UfCrm1663076108RequisiteName}}
     * @param companyAddress       {@code {UfCrm1663076108RequisiteAddress}}
     * @param nip                  {@code {UfCrm1663076108RequisiteNip}}
     * @param regon                {@code {UfCrm1663076108RequisiteRegon}}
     * @param companyRepresentativeName {@code {UfCrm1663076108RequisiteRepresentativeName}}
     * @param companyRepresentativeRole {@code {UfCrm1663076108RequisiteRepresentativeRole}}
     * @param companyEmail         {@code {UfCrm1663076108RequisiteEmail}}
     * @param representativeName   {@code {AssignedName}} — the project MANAGER/FOREMAN's name
     * @param representativeLastName {@code {AssignedLastName}}
     * @param representativeEmail  {@code {UfCrm1692271880Email}}
     * @param representativePhone  {@code {UfCrm1692271880Phone}}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Executor(
            String companyName,
            String companyAddress,
            String nip,
            String regon,
            String companyRepresentativeName,
            String companyRepresentativeRole,
            String companyEmail,
            String representativeName,
            String representativeLastName,
            String representativeEmail,
            String representativePhone) {
    }

    /**
     * The property/area group ({@code PropertyResolver}).
     *
     * @param worksAddress {@code {UfCrm1662541716442}} — the project address
     * @param usableArea   {@code {UfCrm1691765118573}} — the project area (else Σ room floor areas)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Property(String worksAddress, String usableArea) {
    }

    /**
     * The schedule group ({@code ScheduleResolver}).
     *
     * @param beginDate {@code {Begindate}} — project start date (yyyy-MM-dd)
     * @param closeDate {@code {Closedate}} — project end date (yyyy-MM-dd)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Schedule(String beginDate, String closeDate) {
    }

    /**
     * The offer group ({@code OfferTotalsResolver}).
     *
     * @param totalNet {@code {TotalBeforeTax}} — the project's APPROVED offer net total (before VAT)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Offer(String totalNet) {
    }
}
