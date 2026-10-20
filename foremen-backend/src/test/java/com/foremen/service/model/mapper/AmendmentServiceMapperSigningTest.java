package com.foremen.service.model.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.service.document.SignableDocumentService;
import com.foremen.service.document.SigningProgressDto;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.AmendmentSigningProgressModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-09 (Requirements 2.5, 6.3) — read-model mapper test for the signing additions on
 * {@link AmendmentServiceMapper} (task 7.1): the linked document id, the read-through signing
 * progress ({@code SignableDocumentService.progress(...)}), and the effective date.
 *
 * <p>The mapper is a MapStruct abstract class with an injected {@link EntityManager} and
 * {@link SignableDocumentService}; following the repo convention ({@code AuditServiceMapperTest}) the
 * test instantiates the generated {@code AmendmentServiceMapperImpl} and sets the two injected
 * {@code protected} fields (a mocked {@link SignableDocumentService}, a mocked {@link EntityManager})
 * via reflection. The read path never touches the {@link EntityManager}, but it is injected for
 * completeness.
 *
 * <ul>
 *   <li>A {@code SENT_FOR_SIGNING} amendment exposes the linked document id and the read-through
 *       progress (signed/total + outstanding).</li>
 *   <li>An {@code EFFECTIVE} amendment exposes the {@code effectiveDate}.</li>
 *   <li>An {@code OPEN} amendment leaves the signing fields absent/null and never calls the engine.</li>
 * </ul>
 *
 * Validates: Requirements 2.5, 6.3
 */
class AmendmentServiceMapperSigningTest {

    private final AmendmentServiceMapper mapper = new AmendmentServiceMapperImpl();
    private final SignableDocumentService signableDocumentService = mock(SignableDocumentService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    @BeforeEach
    void injectCollaborators() throws Exception {
        setField("signableDocumentService", signableDocumentService);
        setField("entityManager", entityManager);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = AmendmentServiceMapper.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mapper, value);
    }

    private SignableDocumentEntity document(long id) {
        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setId(id);
        return doc;
    }

    private EstimateAmendmentEntity amendment(Long id, AmendmentStatus status) {
        EstimateAmendmentEntity amendment = new EstimateAmendmentEntity();
        amendment.setId(id);
        amendment.setSeqNo(1);
        amendment.setStatus(status);
        return amendment;
    }

    @Test
    @DisplayName("SENT_FOR_SIGNING: exposes linked document id + read-through progress (signed/total + outstanding)")
    void sentForSigning_exposesDocumentIdAndReadThroughProgress() {
        EstimateAmendmentEntity amendment = amendment(10L, AmendmentStatus.SENT_FOR_SIGNING);
        amendment.setSignableDocument(document(77L));

        SigningProgressDto progress = new SigningProgressDto(
                1, 2, false,
                List.of(new SigningProgressDto.SignerRefDto(500L, "CLIENT")));
        when(signableDocumentService.progress(77L)).thenReturn(progress);

        AmendmentServiceExtendedModel model = mapper.toServiceExtendedModel(amendment);

        // Linked document id surfaced (R2.5).
        assertThat(model.getSignableDocumentId()).isEqualTo(77L);

        // Read-through progress projected from the engine (R2.5, R6.2).
        AmendmentSigningProgressModel signing = model.getSigningProgress();
        assertThat(signing).isNotNull();
        assertThat(signing.getSignedCount()).isEqualTo(1);
        assertThat(signing.getTotalCount()).isEqualTo(2);
        assertThat(signing.getOutstanding()).singleElement().satisfies(o -> {
            assertThat(o.getSignerUserId()).isEqualTo(500L);
            assertThat(o.getSignerRole()).isEqualTo("CLIENT");
        });

        // Read-through: the engine is consulted with the linked document id.
        verify(signableDocumentService).progress(77L);
    }

    @Test
    @DisplayName("EFFECTIVE: exposes the effectiveDate")
    void effective_exposesEffectiveDate() {
        EstimateAmendmentEntity amendment = amendment(20L, AmendmentStatus.EFFECTIVE);
        LocalDate effectiveDate = LocalDate.of(2026, 3, 14);
        amendment.setEffectiveDate(effectiveDate);
        amendment.setSignableDocument(document(88L));

        when(signableDocumentService.progress(88L))
                .thenReturn(new SigningProgressDto(2, 2, true, List.of()));

        AmendmentServiceExtendedModel model = mapper.toServiceExtendedModel(amendment);

        assertThat(model.getStatus()).isEqualTo(AmendmentStatus.EFFECTIVE);
        assertThat(model.getEffectiveDate()).isEqualTo(effectiveDate);
        assertThat(model.getSignableDocumentId()).isEqualTo(88L);
        // A fully-signed document has no outstanding signers.
        assertThat(model.getSigningProgress()).isNotNull();
        assertThat(model.getSigningProgress().getSignedCount()).isEqualTo(2);
        assertThat(model.getSigningProgress().getTotalCount()).isEqualTo(2);
        assertThat(model.getSigningProgress().getOutstanding()).isEmpty();
    }

    @Test
    @DisplayName("OPEN: signing fields absent/null and the engine is never consulted")
    void open_signingFieldsAbsentAndEngineNotCalled() {
        EstimateAmendmentEntity amendment = amendment(30L, AmendmentStatus.OPEN);
        // No linked document, no effective date (FOR-06-08 leaves these for FOR-06-09).

        AmendmentServiceExtendedModel model = mapper.toServiceExtendedModel(amendment);

        assertThat(model.getStatus()).isEqualTo(AmendmentStatus.OPEN);
        assertThat(model.getSignableDocumentId()).isNull();
        assertThat(model.getSigningProgress()).isNull();
        assertThat(model.getEffectiveDate()).isNull();

        // No linked document ⇒ the read-through engine is never consulted (R2.1).
        verify(signableDocumentService, never()).progress(any());
    }
}
