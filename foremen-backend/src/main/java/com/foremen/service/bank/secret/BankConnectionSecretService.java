package com.foremen.service.bank.secret;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankConnectionSecretDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionSecretEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.CredentialField;
import com.foremen.dao.model.FeedProvider;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirement 2) — write-only store of per-connection provider credentials.
 *
 * <ul>
 *   <li>{@link #replace} validates the values against the connector's {@link CredentialField}
 *       descriptors, encrypts them with {@link BankSecretCipher} (AES-GCM) and upserts the single
 *       {@code bank_connection_secrets} row (R2.1–2.3). Values are never returned.</li>
 *   <li>{@link #status} exposes only {@code credentialsSet} / {@code credentialsUpdatedAt} (R2.3).</li>
 *   <li>Every write is audited on the connection with the field <em>names</em> only — never the
 *       values or the ciphertext (R2.4).</li>
 *   <li>{@link #assertUsable} / {@link #replace} fail with {@code 409 error.bank.secrets.notConfigured}
 *       when the encryption key is missing and the connector needs credentials; connectors without
 *       credential fields (Salt Edge) are unaffected (R2.5).</li>
 *   <li>{@link #decryptFor} is the internal read path for connectors; never expose it over the API.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BankConnectionSecretService {

    /** Audit operation for a credential write. */
    public static final String AUDIT_OPERATION = "CREDENTIALS_REPLACE";
    /** 422 when values do not match the connector's credential fields. */
    public static final String CREDENTIALS_INVALID = "error.bank.credentials.invalid";
    static final String NOT_FOUND = "error.entity.not.found";
    private static final String SYSTEM_USER = "SYSTEM";
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final BankConnectionSecretDao secretDao;
    private final BankConnectionDao connectionDao;
    private final BankFeedConnectorRegistry connectorRegistry;
    private final BankSecretCipher cipher;
    private final AuditLogDao auditLogDao;

    /** Read-side view: whether credentials are stored and when they were last replaced. */
    public record CredentialsStatus(boolean credentialsSet, OffsetDateTime credentialsUpdatedAt) {
        public static final CredentialsStatus NOT_SET = new CredentialsStatus(false, null);
    }

    /** {@code credentialsSet} / {@code credentialsUpdatedAt} for one connection; never the values. */
    @Transactional(readOnly = true)
    public CredentialsStatus status(Long connectionId) {
        return secretDao.findById(connectionId)
                .map(s -> new CredentialsStatus(true, s.getUpdatedAt()))
                .orElse(CredentialsStatus.NOT_SET);
    }

    /** {@code true} when the provider's connector declares per-connection credential fields. */
    public boolean requiresCredentials(FeedProvider provider) {
        return !credentialFields(provider).isEmpty();
    }

    /**
     * Fails with {@code 409 error.bank.secrets.notConfigured} when the provider needs credentials but
     * the encryption key is not configured (R2.5). No-op for providers without credential fields.
     */
    public void assertUsable(FeedProvider provider) {
        if (requiresCredentials(provider) && !cipher.isConfigured()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, BankSecretCipher.NOT_CONFIGURED_CODE);
        }
    }

    /**
     * Replaces the credentials of a connection (write-only). Every declared field must be present and
     * non-blank and no undeclared field is accepted.
     *
     * @throws ForemenApiException 404 unknown connection; 409 {@code error.bank.secrets.notConfigured}
     *                             when the key is missing; 422 {@link #CREDENTIALS_INVALID}
     */
    @Transactional
    public CredentialsStatus replace(Long connectionId, Map<String, String> values) {
        BankConnectionEntity connection = connectionDao.findById(connectionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        List<CredentialField> fields = credentialFields(connection.getProvider());
        if (fields.isEmpty()) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CREDENTIALS_INVALID);
        }
        if (!cipher.isConfigured()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, BankSecretCipher.NOT_CONFIGURED_CODE);
        }
        Map<String, String> normalized = validate(fields, values);

        BankSecretCipher.Encrypted encrypted = cipher.encrypt(normalized);
        Optional<BankConnectionSecretEntity> existing = secretDao.findById(connectionId);
        BankConnectionSecretEntity entity = existing.orElseGet(() -> {
            BankConnectionSecretEntity created = new BankConnectionSecretEntity();
            created.setConnectionId(connectionId);
            return created;
        });
        String user = performingUser();
        entity.setCiphertext(encrypted.ciphertext());
        entity.setIv(encrypted.iv());
        entity.setKeyVersion(encrypted.keyVersion());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setUpdatedBy(user);
        BankConnectionSecretEntity saved = secretDao.save(entity);

        audit(connectionId, existing.isPresent(), normalized.keySet(), encrypted.keyVersion(), user);
        return new CredentialsStatus(true, saved.getUpdatedAt());
    }

    /**
     * Internal read path for connectors: the decrypted credential map, or empty when none is stored.
     *
     * @throws ForemenApiException 409 when the key is not configured
     * @throws BankSecretCipherException on tampering / wrong key / unknown key version
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, String>> decryptFor(Long connectionId) {
        return secretDao.findById(connectionId)
                .map(s -> cipher.decrypt(new BankSecretCipher.Encrypted(
                        s.getCiphertext(), s.getIv(), s.getKeyVersion())));
    }

    private List<CredentialField> credentialFields(FeedProvider provider) {
        List<CredentialField> fields = connectorRegistry.find(provider)
                .map(BankFeedConnector::credentialFields)
                .orElse(List.of());
        return fields == null ? List.of() : fields;
    }

    private static Map<String, String> validate(List<CredentialField> fields, Map<String, String> values) {
        if (values == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CREDENTIALS_INVALID);
        }
        Set<String> declared = fields.stream().map(CredentialField::name).collect(Collectors.toSet());
        if (!declared.containsAll(values.keySet())) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CREDENTIALS_INVALID);
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (CredentialField field : fields) {
            String value = values.get(field.name());
            if (value == null || value.isBlank()) {
                throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, CREDENTIALS_INVALID);
            }
            normalized.put(field.name(), field.secret() ? value : value.trim());
        }
        return normalized;
    }

    /** One audit row on the connection: field names + key version only, never values (R2.4). */
    private void audit(Long connectionId, boolean replaced, Set<String> fieldNames, short keyVersion, String user) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("credentialsSet", replaced);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("credentialsSet", true);
        after.put("fields", new ArrayList<>(new TreeSet<>(fieldNames)));
        after.put("keyVersion", keyVersion);

        AuditLogEntity row = new AuditLogEntity();
        row.setEntityClass(BankConnectionEntity.class.getSimpleName());
        row.setEntityId(connectionId);
        row.setOperation(AUDIT_OPERATION);
        row.setPerformedBy(user);
        row.setPerformedAt(LocalDateTime.now());
        row.setSnapshotBefore(snapshot(before));
        row.setSnapshotAfter(snapshot(after));
        auditLogDao.save(row);
    }

    private static String snapshot(Map<String, Object> fields) {
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private static String performingUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return SYSTEM_USER;
    }
}
