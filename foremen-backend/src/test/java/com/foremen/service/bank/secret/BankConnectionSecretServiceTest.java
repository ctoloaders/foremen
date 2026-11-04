package com.foremen.service.bank.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankConnectionSecretDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionSecretEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.CredentialField;

/**
 * Unit tests for {@link BankConnectionSecretService} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4, 2.5</b>
 */
@ExtendWith(MockitoExtension.class)
class BankConnectionSecretServiceTest {

    private static final Long CONNECTION_ID = 42L;
    private static final String SECRET_VALUE = "super-secret-ąę";

    @Mock private BankConnectionSecretDao secretDao;
    @Mock private BankConnectionDao connectionDao;
    @Mock private AuditLogDao auditLogDao;
    @Mock private BankFeedConnector stubConnector;
    @Mock private BankFeedConnector saltEdgeConnector;

    private BankSecretCipher cipher;
    private BankConnectionSecretService service;

    @BeforeEach
    void setUp() {
        lenient().when(stubConnector.provider()).thenReturn(FeedProvider.STUB);
        lenient().when(stubConnector.credentialFields()).thenReturn(List.of(
                new CredentialField("clientId", "bank.credentials.clientId", false),
                new CredentialField("clientSecret", "bank.credentials.clientSecret", true)));
        lenient().when(saltEdgeConnector.provider()).thenReturn(FeedProvider.SALT_EDGE);
        lenient().when(saltEdgeConnector.credentialFields()).thenReturn(List.of());
        cipher = new BankSecretCipher(randomKey());
        service = newService(cipher);
        lenient().when(secretDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private BankConnectionSecretService newService(BankSecretCipher c) {
        BankFeedConnectorRegistry registry = new BankFeedConnectorRegistry(List.of(stubConnector, saltEdgeConnector));
        return new BankConnectionSecretService(secretDao, connectionDao, registry, c, auditLogDao);
    }

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static Map<String, String> validValues() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("clientId", " client-1 ");
        m.put("clientSecret", SECRET_VALUE);
        return m;
    }

    private void givenConnection(FeedProvider provider) {
        BankConnectionEntity connection = new BankConnectionEntity();
        connection.setProvider(provider);
        when(connectionDao.findById(CONNECTION_ID)).thenReturn(Optional.of(connection));
    }

    @Test
    void replace_createsEncryptedRow_andDecryptForRoundTrips() {
        givenConnection(FeedProvider.STUB);
        when(secretDao.findById(CONNECTION_ID)).thenReturn(Optional.empty());

        BankConnectionSecretService.CredentialsStatus status = service.replace(CONNECTION_ID, validValues());

        assertThat(status.credentialsSet()).isTrue();
        assertThat(status.credentialsUpdatedAt()).isNotNull();
        ArgumentCaptor<BankConnectionSecretEntity> saved = ArgumentCaptor.forClass(BankConnectionSecretEntity.class);
        verify(secretDao).save(saved.capture());
        BankConnectionSecretEntity row = saved.getValue();
        assertThat(row.getConnectionId()).isEqualTo(CONNECTION_ID);
        assertThat(row.getIv()).hasSize(BankSecretCipher.IV_LENGTH);
        assertThat(row.getKeyVersion()).isEqualTo(BankSecretCipher.KEY_VERSION);
        assertThat(row.getUpdatedBy()).isEqualTo("7");
        assertThat(new String(row.getCiphertext(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("super-secret");

        when(secretDao.findById(CONNECTION_ID)).thenReturn(Optional.of(row));
        assertThat(service.decryptFor(CONNECTION_ID)).contains(Map.of(
                "clientId", "client-1", "clientSecret", SECRET_VALUE));
    }

    @Test
    void replace_overwritesExistingRow() {
        givenConnection(FeedProvider.STUB);
        BankConnectionSecretEntity existing = new BankConnectionSecretEntity();
        existing.setConnectionId(CONNECTION_ID);
        existing.setCiphertext(new byte[] {1});
        existing.setIv(new byte[12]);
        existing.setUpdatedAt(OffsetDateTime.now().minusDays(3));
        when(secretDao.findById(CONNECTION_ID)).thenReturn(Optional.of(existing));

        service.replace(CONNECTION_ID, validValues());

        verify(secretDao).save(existing);
        assertThat(existing.getCiphertext()).isNotEqualTo(new byte[] {1});
        assertThat(existing.getUpdatedAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void replace_auditsWithoutSecretValues() {
        givenConnection(FeedProvider.STUB);
        when(secretDao.findById(CONNECTION_ID)).thenReturn(Optional.empty());

        service.replace(CONNECTION_ID, validValues());

        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao).save(audit.capture());
        AuditLogEntity row = audit.getValue();
        assertThat(row.getEntityClass()).isEqualTo("BankConnectionEntity");
        assertThat(row.getEntityId()).isEqualTo(CONNECTION_ID);
        assertThat(row.getOperation()).isEqualTo(BankConnectionSecretService.AUDIT_OPERATION);
        assertThat(row.getPerformedBy()).isEqualTo("7");
        assertThat(row.getPerformedAt()).isNotNull();
        String snapshots = row.getSnapshotBefore() + row.getSnapshotAfter();
        assertThat(snapshots).contains("clientSecret").contains("\"credentialsSet\":true")
                .doesNotContain(SECRET_VALUE).doesNotContain("client-1");
    }

    @Test
    void status_reportsOnlyFlagAndTimestamp() {
        assertThat(service.status(CONNECTION_ID)).isEqualTo(BankConnectionSecretService.CredentialsStatus.NOT_SET);

        BankConnectionSecretEntity row = new BankConnectionSecretEntity();
        OffsetDateTime at = OffsetDateTime.now();
        row.setUpdatedAt(at);
        when(secretDao.findById(CONNECTION_ID)).thenReturn(Optional.of(row));
        assertThat(service.status(CONNECTION_ID))
                .isEqualTo(new BankConnectionSecretService.CredentialsStatus(true, at));
    }

    @Test
    void replace_missingKey_throws409_andWritesNothing() {
        BankConnectionSecretService unconfigured = newService(new BankSecretCipher(""));
        givenConnection(FeedProvider.STUB);

        assertThatThrownBy(() -> unconfigured.replace(CONNECTION_ID, validValues()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.bank.secrets.notConfigured");
                });
        verify(secretDao, never()).save(any());
        verify(auditLogDao, never()).save(any());
    }

    @Test
    void assertUsable_onlyFailsForConnectorsNeedingCredentials() {
        BankConnectionSecretService unconfigured = newService(new BankSecretCipher(""));

        unconfigured.assertUsable(FeedProvider.SALT_EDGE);
        assertThat(unconfigured.requiresCredentials(FeedProvider.SALT_EDGE)).isFalse();
        assertThat(unconfigured.requiresCredentials(FeedProvider.STUB)).isTrue();
        assertThatThrownBy(() -> unconfigured.assertUsable(FeedProvider.STUB))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankSecretCipher.NOT_CONFIGURED_CODE));
        service.assertUsable(FeedProvider.STUB);
    }

    @Test
    void replace_rejectsMissingBlankOrUnknownFields() {
        givenConnection(FeedProvider.STUB);

        Map<String, String> blank = validValues();
        blank.put("clientSecret", "  ");
        Map<String, String> unknown = validValues();
        unknown.put("extra", "x");

        for (Map<String, String> bad : List.of(Map.of("clientId", "a"), blank, unknown)) {
            assertThatThrownBy(() -> service.replace(CONNECTION_ID, bad))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getMessageCode()).isEqualTo(BankConnectionSecretService.CREDENTIALS_INVALID);
                    });
        }
        verify(secretDao, never()).save(any());
    }

    @Test
    void replace_rejectsConnectorWithoutCredentialFields() {
        givenConnection(FeedProvider.SALT_EDGE);

        assertThatThrownBy(() -> service.replace(CONNECTION_ID, Map.of("a", "b")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void replace_unknownConnection_throws404() {
        when(connectionDao.findById(CONNECTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(CONNECTION_ID, validValues()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void decryptFor_noRow_isEmpty() {
        assertThat(service.decryptFor(CONNECTION_ID)).isEmpty();
    }
}
