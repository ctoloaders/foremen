package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.roomsimport.ai.RoomsVisionClient;

import jakarta.persistence.EntityManager;

/**
 * Unit tests of {@link RoomsImportService}: the single-job rule, 429 mapping, replacement, cancel.
 * Validates: Requirements 1.3, 1.8, 2.5, 19.1-19.3, 24.1, 24.2, 24.7.
 */
class RoomsImportServiceTest {

    private final RoomsImportJobDao dao = mock(RoomsImportJobDao.class);
    private final RoomsImportStorage storage = mock(RoomsImportStorage.class);
    private final RoomsVisionClient vision = mock(RoomsVisionClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final EntityManager em = mock(EntityManager.class);
    private final RoomsImportJobMapper mapper = new RoomsImportJobMapper(new ObjectMapper().findAndRegisterModules());
    private RoomsImportService service;

    @BeforeEach
    void setUp() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(vision.isEnabled()).thenReturn(true);
        when(storage.isConfigured()).thenReturn(true);
        when(em.getReference(ProjectEntity.class, 1L)).thenReturn(new ProjectEntity());
        when(em.getReference(UserEntity.class, 2L)).thenReturn(new UserEntity());
        when(dao.save(any(RoomsImportJobEntity.class))).thenAnswer(inv -> {
            RoomsImportJobEntity j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(100L);
            }
            return j;
        });
        service = new RoomsImportService(dao, mapper, storage, vision, events, tm);
        ReflectionTestUtils.setField(service, "entityManager", em);
    }

    private static RoomsImportJobEntity job(Long id, RoomsImportStatus status) {
        RoomsImportJobEntity j = new RoomsImportJobEntity();
        j.setId(id);
        j.setStatus(status);
        j.setStartedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        j.setInputs(List.of(Map.of("fileName", "a.txt", "contentType", "text/plain", "sizeBytes", 3,
                "objectKey", "rooms-import/" + id + "/in/x.txt")));
        return j;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", "abc".getBytes());
    }

    // --- start -------------------------------------------------------------------------------

    @Test
    void start_createsQueuedJob_storesInputsUnderTheJob_andPublishesEvent() {
        when(dao.findByProjectIdForUpdate(1L)).thenReturn(Optional.empty());
        when(storage.storeInput(any(), any())).thenReturn("rooms-import/100/in/uuid.txt");

        var result = service.start(1L, 2L, "  3 rooms ", List.of(file("../evil\\plan.txt")));

        assertThat(result.jobId()).isEqualTo(100L);
        assertThat(result.status()).isEqualTo(RoomsImportStatus.QUEUED);
        ArgumentCaptor<RoomsImportJobEntity> saved = ArgumentCaptor.forClass(RoomsImportJobEntity.class);
        verify(dao, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        RoomsImportJobEntity last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getInputs()).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("objectKey", "rooms-import/100/in/uuid.txt");
            assertThat(m).containsEntry("fileName", "plan.txt"); // client name is sanitised metadata
        });
        verify(storage).storeInput(org.mockito.ArgumentMatchers.eq(100L), any());
        verify(events).publishEvent(new RoomsImportRequested(100L));
    }

    @Test
    void start_withActiveJob_throws429WithParams_andStoresNothing() {
        when(dao.findByProjectIdForUpdate(1L)).thenReturn(Optional.of(job(55L, RoomsImportStatus.MEASURING)));

        assertThatThrownBy(() -> service.start(1L, 2L, "x", List.of(file("a.txt"))))
                .isInstanceOfSatisfying(RoomsImportInProgressException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessageCode()).isEqualTo(RoomsImportErrors.IN_PROGRESS);
                    assertThat(e.getJobId()).isEqualTo(55L);
                    assertThat(e.getStage()).isEqualTo(RoomsImportStatus.MEASURING);
                    assertThat(e.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4));
                });

        verify(storage, never()).storeInput(anyLong(), any());
        verify(dao, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void start_replacesATerminalJob_deletingItBeforeInsertingTheNewOne() {
        RoomsImportJobEntity old = job(55L, RoomsImportStatus.COMPLETED);
        when(dao.findByProjectIdForUpdate(1L)).thenReturn(Optional.of(old));
        when(dao.deleteByJobId(55L)).thenReturn(1);

        service.start(1L, 2L, "x", List.of());

        InOrder order = inOrder(dao, storage);
        order.verify(dao).deleteByJobId(55L);
        order.verify(storage).deleteJobObjectsAfterCommit(org.mockito.ArgumentMatchers.eq(55L),
                org.mockito.ArgumentMatchers.argThat((Set<String> keys) -> keys.contains("rooms-import/55/in/x.txt")));
        order.verify(dao, org.mockito.Mockito.atLeastOnce()).save(any(RoomsImportJobEntity.class));
        verify(events).publishEvent(new RoomsImportRequested(100L));
    }

    @Test
    void start_uniqueViolationRace_isMappedTo429_neverA500() {
        when(dao.findByProjectIdForUpdate(1L)).thenReturn(Optional.empty());
        when(dao.save(any(RoomsImportJobEntity.class))).thenThrow(new DataIntegrityViolationException("uq"));
        when(dao.findByProjectId(1L)).thenReturn(Optional.of(job(77L, RoomsImportStatus.QUEUED)));

        assertThatThrownBy(() -> service.start(1L, 2L, "x", List.of()))
                .isInstanceOfSatisfying(RoomsImportInProgressException.class,
                        e -> assertThat(e.getJobId()).isEqualTo(77L));
        verifyNoInteractions(events);
    }

    @Test
    void start_whenFeatureDisabled_is503_andTouchesNothing() {
        when(vision.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.start(1L, 2L, "x", List.of()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessageCode()).isEqualTo(RoomsImportErrors.UNAVAILABLE);
                });
        verifyNoInteractions(dao, events);
    }

    @Test
    void start_whenStorageFails_cleansUpAlreadyStoredInputs() {
        when(dao.findByProjectIdForUpdate(1L)).thenReturn(Optional.empty());
        when(storage.storeInput(anyLong(), any())).thenReturn("rooms-import/100/in/one.txt")
                .thenThrow(new IllegalStateException("bucket down"));

        assertThatThrownBy(() -> service.start(1L, 2L, "x", List.of(file("a.txt"), file("b.txt"))))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).deleteNow(List.of("rooms-import/100/in/one.txt"));
        verifyNoInteractions(events);
    }

    // --- cancel / discard ----------------------------------------------------------------------

    @Test
    void cancel_activeJob_deletesConditionally_andRemovesObjects() {
        when(dao.findById(55L)).thenReturn(Optional.of(job(55L, RoomsImportStatus.EXTRACTING)));
        when(dao.deleteIfActive(55L, RoomsImportJobDao.TERMINAL)).thenReturn(1);

        service.cancel(55L);

        verify(storage).deleteJobObjectsAfterCommit(org.mockito.ArgumentMatchers.eq(55L), any());
    }

    @Test
    void cancel_terminalJob_is409NotCancellable_andKeepsTheJob() {
        when(dao.findById(55L)).thenReturn(Optional.of(job(55L, RoomsImportStatus.COMPLETED)));
        when(dao.deleteIfActive(55L, RoomsImportJobDao.TERMINAL)).thenReturn(0);
        when(dao.existsById(55L)).thenReturn(true);

        assertThatThrownBy(() -> service.cancel(55L)).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessageCode()).isEqualTo(RoomsImportErrors.NOT_CANCELLABLE);
        });
        verify(storage, never()).deleteJobObjectsAfterCommit(anyLong(), any());
    }

    @Test
    void cancel_missingJob_is404() {
        when(dao.findById(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(55L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void cancel_jobVanishesBetweenReadAndDelete_is404() {
        when(dao.findById(55L)).thenReturn(Optional.of(job(55L, RoomsImportStatus.EXTRACTING)));
        when(dao.deleteIfActive(55L, RoomsImportJobDao.TERMINAL)).thenReturn(0);
        when(dao.existsById(55L)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(55L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void discard_isIdempotent_andCancelsActiveJobs() {
        when(dao.findById(55L)).thenReturn(Optional.empty());
        service.discard(55L); // already gone: no error, no deletes
        verify(dao, never()).deleteByJobId(anyLong());

        when(dao.findById(56L)).thenReturn(Optional.of(job(56L, RoomsImportStatus.MEASURING)));
        when(dao.deleteByJobId(56L)).thenReturn(1);
        service.discard(56L);
        verify(storage).deleteJobObjectsAfterCommit(org.mockito.ArgumentMatchers.eq(56L), any());
    }

    @Test
    void findCurrent_delegatesToTheProjectLookup() {
        RoomsImportJobEntity j = job(55L, RoomsImportStatus.COMPLETED);
        when(dao.findByProjectId(1L)).thenReturn(Optional.of(j));

        assertThat(service.findCurrent(1L)).containsSame(j);
        assertThat(service.findCurrent(9L)).isEmpty();
    }

    @Test
    void sanitizeFileName_stripsPathsAndControlCharacters() {
        assertThat(RoomsImportService.sanitizeFileName("C:\\x\\y\\plan.pdf")).isEqualTo("plan.pdf");
        assertThat(RoomsImportService.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(RoomsImportService.sanitizeFileName("a\u0000b.txt")).isEqualTo("ab.txt");
        assertThat(RoomsImportService.sanitizeFileName("  ")).isEqualTo("file");
        assertThat(RoomsImportService.sanitizeFileName(null)).isEqualTo("file");
    }
}
