package com.foremen.service.procurement.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.controller.model.GoodsReceiptMediaDto;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.GoodsReceiptMediaDao;
import com.foremen.dao.model.GoodsReceiptEntity;
import com.foremen.dao.model.GoodsReceiptMediaEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageReferenceLookup;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.room.media.RoomMediaSupport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * FOR-11-05 task 3.5 - {@link GoodsReceiptMediaService} (magic bytes, limits, nothing stored on rejection),
 * the {@link ImageReferenceLookup} registration of {@code goods_receipt_media.storage_uri}, and the DTO
 * guard (no price, amount, VAT or storage key).
 *
 * <p>Validates: Requirements 7.2, 7.5, 9.5, 16.2
 */
class GoodsReceiptMediaServiceTest {

    private static final byte[] JPEG =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
    private static final long RECEIPT_ID = 5L;

    private final GoodsReceiptDao receiptDao = mock(GoodsReceiptDao.class);
    private final GoodsReceiptMediaDao mediaDao = mock(GoodsReceiptMediaDao.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final RoomMediaSupport support = mock(RoomMediaSupport.class);
    private final ProcurementProjectAccess access = mock(ProcurementProjectAccess.class);
    private final AuditLogDao auditDao = mock(AuditLogDao.class);
    private final GoodsReceiptProperties props = new GoodsReceiptProperties(
            new GoodsReceiptProperties.Media(3, 4, DataSize.ofBytes(100)), null);
    private final GoodsReceiptMediaService service =
            new GoodsReceiptMediaService(receiptDao, mediaDao, storage, support, access, auditDao, props);

    @BeforeEach
    void setUp() {
        // ADMIN caller: scope and void-window checks pass; those rules are covered elsewhere.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        GoodsReceiptEntity receipt = new GoodsReceiptEntity();
        ProjectEntity project = new ProjectEntity();
        project.setId(10L);
        receipt.setProject(project);
        UserEntity author = new UserEntity();
        author.setId(1L);
        receipt.setReceivedBy(author);
        receipt.setOccurredAt(OffsetDateTime.now());
        receipt.setId(RECEIPT_ID);
        when(receiptDao.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));
        when(mediaDao.countByReceiptId(RECEIPT_ID)).thenReturn(0L);
        when(storage.storeObject(any(), anyString())).thenReturn("goods-receipts/key-1.jpg");
        when(mediaDao.save(any())).thenAnswer(inv -> {
            GoodsReceiptMediaEntity m = inv.getArgument(0);
            m.setId(77L);
            return m;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MockMultipartFile file(String type, byte[] bytes) {
        return new MockMultipartFile("files", "p.jpg", type, bytes);
    }

    private void assertRejected(List<MultipartFile> files, String key) {
        assertThatThrownBy(() -> service.upload(RECEIPT_ID, files))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).contains(key));
        verifyNoInteractions(storage);
        verify(mediaDao, never()).save(any());
    }

    @Test
    void validJpegIsStoredAndReturnsMetadataOnly() {
        List<GoodsReceiptMediaDto> result = service.upload(RECEIPT_ID, List.of(file("image/jpeg", JPEG)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(result.get(0).sizeBytes()).isEqualTo(JPEG.length);
        verify(storage).storeObject(any(), Mockito.eq("goods-receipts"));
    }

    @Test
    void declaredTypeMustMatchMagicBytes() {
        assertRejected(List.of(file("image/png", JPEG)), "media.type");
    }

    @Test
    void heicIsRejected() {
        assertRejected(List.of(file("image/heic", HEIC)), "media.type");
    }

    @Test
    void oversizedFileIsRejectedAndNothingIsStored() {
        assertRejected(List.of(file("image/png", Arrays.copyOf(PNG, 101))), "media.size");
    }

    @Test
    void tooManyFilesPerRequestIsRejectedAndNothingIsStored() {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(file("image/jpeg", JPEG));
        }
        assertRejected(files, "media.count");
    }

    @Test
    void perEventLimitCountsExistingPhotos() {
        when(mediaDao.countByReceiptId(RECEIPT_ID)).thenReturn(3L);
        assertRejected(List.of(file("image/jpeg", JPEG), file("image/jpeg", JPEG)), "media.count");
    }

    @Test
    void oneBadFileInABatchStoresNoneOfThem() {
        assertRejected(List.of(file("image/jpeg", JPEG), file("image/png", JPEG)), "media.type");
    }

    @Test
    void emptyUploadIsRejected() {
        assertRejected(List.of(), "media.count");
    }

    // --- ImageReferenceLookup registration (R7.2) ---

    @Test
    void imageReferenceLookupReportsGoodsReceiptMediaKeyAsReferenced() {
        EntityManager em = mock(EntityManager.class);
        Query columnCheck = mock(Query.class);
        when(columnCheck.getSingleResult()).thenReturn(1L);
        Query zero = mock(Query.class);
        when(zero.getSingleResult()).thenReturn(0L);
        Query hit = mock(Query.class);
        when(hit.getSingleResult()).thenReturn(1L);
        // Every column exists; only goods_receipt_media holds the key.
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("information_schema")) {
                return columnCheck;
            }
            return sql.contains("FROM goods_receipt_media ") ? hit : zero;
        });
        ImageReferenceLookup lookup = new ImageReferenceLookup();
        ReflectionTestUtils.setField(lookup, "entityManager", em);

        assertThat(lookup.isReferenced("goods-receipts/key-1.jpg")).isTrue();
        verify(em).createNativeQuery("SELECT COUNT(*) FROM goods_receipt_media WHERE storage_uri = :key");
    }

    // --- DTO guard (R7.5, R9.5, R16.2) ---

    private static final Set<String> FORBIDDEN = Set.of(
            "price", "amount", "vat", "cost", "total", "storage", "uri", "objectkey", "url", "margin");

    @Test
    void goodsReceiptDtosExposeNoPriceAmountVatOrStorageKey() {
        List<String> names = List.of(
                "GoodsReceiptMediaDto", "GoodsReceiptDto", "GoodsReceiptRequest", "GoodsReceiptBatchResult",
                "GoodsReceiptFilter", "GoodsReceiptVoidRequest", "PositionLedgerDto", "ToReceiveDto");
        List<Class<?>> present = new ArrayList<>();
        for (String n : names) {
            try {
                present.add(Class.forName("com.foremen.controller.model." + n));
            } catch (ClassNotFoundException ignored) {
                // DTO belongs to a later task; the guard covers it once it exists.
            }
        }
        assertThat(present).contains(GoodsReceiptMediaDto.class);
        for (Class<?> c : present) {
            checkNoForbidden(c, new HashSet<>());
        }
    }

    private static void checkNoForbidden(Class<?> type, Set<Class<?>> seen) {
        if (!seen.add(type) || !type.getName().startsWith("com.foremen.")) {
            return;
        }
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            String name = f.getName().toLowerCase();
            for (String bad : FORBIDDEN) {
                assertThat(name).as("%s.%s", type.getSimpleName(), f.getName()).doesNotContain(bad);
            }
            checkNoForbidden(f.getType(), seen);
            if (f.getGenericType() instanceof ParameterizedType pt) {
                for (Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> ac) {
                        checkNoForbidden(ac, seen);
                    }
                }
            }
        }
    }
}
