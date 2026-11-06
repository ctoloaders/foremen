package com.foremen.service.roomsimport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.service.roomsimport.model.DraftMedia;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportResult;

/**
 * FOR-05-02 — converts between the typed records ({@link StoredInput}, {@link ImportResult}) and the
 * generic JSON structures the {@link RoomsImportJobEntity} stores, and resolves the object keys a job
 * owns. Draft media ids are the file part of the object key ({@code <uuid>.png}); the key is rebuilt
 * from the job id and the id, and only ids listed in the job result are addressable (design B7a).
 */
@Component
public class RoomsImportJobMapper {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /**
     * Spring constructor. The application has no Jackson 2 {@link ObjectMapper} bean (Boot 4 wires
     * Jackson 3), so — like the other services here — the mapper owns a private instance.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RoomsImportJobMapper() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public RoomsImportJobMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> inputsToMaps(List<StoredInput> inputs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredInput in : inputs) {
            out.add(objectMapper.convertValue(in, MAP));
        }
        return out;
    }

    public List<StoredInput> readInputs(RoomsImportJobEntity job) {
        List<StoredInput> out = new ArrayList<>();
        if (job.getInputs() != null) {
            for (Map<String, Object> m : job.getInputs()) {
                out.add(objectMapper.convertValue(m, StoredInput.class));
            }
        }
        return out;
    }

    public Map<String, Object> resultToMap(ImportResult result) {
        return objectMapper.convertValue(result, MAP);
    }

    /** The typed result, or {@code null} while the job has none. */
    public ImportResult readResult(RoomsImportJobEntity job) {
        return job.getResult() == null ? null : objectMapper.convertValue(job.getResult(), ImportResult.class);
    }

    /** Storage key of a draft media object. */
    public static String mediaKey(Long jobId, String mediaId) {
        return RoomsImportErrors.jobNamespace(jobId) + "/media/" + mediaId;
    }

    /** Media id (file part) of a stored media object key. */
    public static String mediaIdOf(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash < 0 ? objectKey : objectKey.substring(slash + 1);
    }

    /** Every object key the job references: its inputs and the draft media in its result. */
    public Set<String> objectKeys(RoomsImportJobEntity job) {
        Set<String> keys = new LinkedHashSet<>();
        for (StoredInput in : readInputs(job)) {
            if (in.objectKey() != null) {
                keys.add(in.objectKey());
            }
        }
        try {
            ImportResult result = readResult(job);
            if (result != null && result.rooms() != null) {
                for (DraftRoom room : result.rooms()) {
                    if (room.media() == null) {
                        continue;
                    }
                    for (DraftMedia m : room.media()) {
                        if (m.mediaId() != null) {
                            keys.add(mediaKey(job.getId(), m.mediaId()));
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // an unreadable result must not block cleanup; the prefix listing catches the rest
        }
        return keys;
    }
}
