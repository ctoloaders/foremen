package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.foremen.dao.model.MatchStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.CompanyPurchasesService.Filter;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.procurement.LinkStatus;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-03 task 4.5 - focused unit tests for {@link CompanyPurchasesService}: the filter conflict
 * (R4.3), paging bounds (R4.8), the bulk link-count fold (R14.1, property 4), and the unchecked
 * delegation of delete (R6.2, R6.3).
 */
class CompanyPurchasesServiceTest {

    private final EntityManager em = mock(EntityManager.class);
    private final ProcurementDocumentService documentService = mock(ProcurementDocumentService.class);
    private final CompanyPurchasesService service = new CompanyPurchasesService(
            em, documentService, mock(ProcurementDocumentServiceMapper.class),
            mock(ImageStorage.class), mock(PlannedMaterialCandidateProvider.class), mock(DocumentRelinkService.class));

    @Test
    void projectAndNoProjectTogetherIsBadRequest() {
        Filter conflict = new Filter(5L, true, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.listDocuments(conflict, 0, 25))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessageCode()).isEqualTo("error.companyProcurement.filter.conflict");
                });
        assertThatThrownBy(() -> service.listLines(conflict, 0, 25))
                .isInstanceOf(ForemenApiException.class);
    }

    @Test
    void pageSizeDefaultsToTwentyFiveAndCapsAtOneHundred() {
        assertThat(CompanyPurchasesService.pageable(0, 0).getPageSize()).isEqualTo(25);
        assertThat(CompanyPurchasesService.pageable(0, 1000).getPageSize()).isEqualTo(100);
        assertThat(CompanyPurchasesService.pageable(0, 40).getPageSize()).isEqualTo(40);
        Pageable negativePage = CompanyPurchasesService.pageable(-3, 10);
        assertThat(negativePage.getPageNumber()).isZero();
    }

    @Test
    void linkCountsFoldPerDocumentAndSumToLineCount() {
        // doc 1 (project): 2 AUTO, UNMATCHED 3 lines of which 1 classified, MANUAL_UNMATCHED 1 unclassified.
        // doc 2 (no project): 4 UNMATCHED lines, 2 carrying a category -> all NO_PROJECT.
        List<Object[]> rows = List.of(
                new Object[] {1L, MatchStatus.AUTO, 0L, 2L},
                new Object[] {1L, MatchStatus.UNMATCHED, 1L, 3L},
                new Object[] {1L, MatchStatus.MANUAL_UNMATCHED, 0L, 1L},
                new Object[] {2L, MatchStatus.UNMATCHED, 2L, 4L});

        Map<Long, Map<LinkStatus, Integer>> result =
                CompanyPurchasesService.aggregateLinkCounts(rows, Set.of(1L));

        assertThat(result.get(1L)).containsEntry(LinkStatus.LINKED, 2)
                .containsEntry(LinkStatus.CLASSIFIED, 1)
                .containsEntry(LinkStatus.UNLINKED, 3)
                .containsEntry(LinkStatus.NO_PROJECT, 0);
        assertThat(result.get(1L).values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(6);
        assertThat(result.get(2L)).containsEntry(LinkStatus.NO_PROJECT, 4)
                .containsEntry(LinkStatus.LINKED, 0);
    }

    @Test
    void deleteDelegatesToTheUncheckedVariant() {
        service.delete(9L);

        verify(documentService).deleteUnchecked(9L);
    }
}
