package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.controller.model.MetadataResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.util.EntityMetadataResolver;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Generic full-CRUD controller interface.
 * Concrete controllers implement this interface, provide {@link #getMapper()} and {@link #getService()},
 * and inherit all REST endpoint default methods.
 *
 * @param <ServiceModel>         the service-layer list/summary model
 * @param <ServiceExtendedModel> the service-layer detailed model (all locales)
 * @param <DtoModel>             the DTO returned in paginated lists
 * @param <DtoExtendedModel>     the DTO returned for single-entity detail views
 * @param <DaoModel>             the JPA entity class
 * @param <ID>                   the entity identifier type
 * @param <CreateRequestModel>   the inbound create request DTO
 * @param <CreateResponseModel>  the outbound create response DTO
 * @param <UpdateRequestModel>   the inbound update request DTO
 * @param <UpdateResponseModel>  the outbound update response DTO
 */
public interface AdminController<
        ServiceModel,
        ServiceExtendedModel,
        DtoModel,
        DtoExtendedModel,
        DaoModel,
        ID,
        CreateRequestModel,
        CreateResponseModel,
        UpdateRequestModel,
        UpdateResponseModel> {

    ControllerToServiceMapper<ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel,
            CreateRequestModel, CreateResponseModel, UpdateRequestModel, UpdateResponseModel> getMapper();

    AdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID> getService();

    /**
     * Hook for concrete controllers to append/prepend additional query conditions
     * (e.g., tenant isolation, status filters) without overriding the full find method.
     * Default is passthrough — returns query unchanged.
     */
    default String addCustomQueryCondition(String query) {
        return query;
    }

    // --- CREATE ---

    @PostMapping
    @PermissionOperation("CREATE")
    default ResponseEntity<CreateResponseModel> create(@Valid @RequestBody CreateRequestModel request) {
        ServiceExtendedModel serviceModel = getMapper().toServiceExtendedModel(request);
        ServiceExtendedModel created = getService().create(serviceModel);
        CreateResponseModel response = getMapper().toCreateResponse(created);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/bulk")
    @PermissionOperation("CREATE")
    default ResponseEntity<List<CreateResponseModel>> createBulk(
            @Valid @RequestBody List<CreateRequestModel> requests) {
        List<ServiceExtendedModel> serviceModels = requests.stream()
                .map(getMapper()::toServiceExtendedModel)
                .toList();
        List<ServiceExtendedModel> created = getService().create(serviceModels);
        List<CreateResponseModel> response = created.stream()
                .map(getMapper()::toCreateResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    // --- UPDATE ---

    @PutMapping("/{id}")
    @PermissionOperation("UPDATE")
    default ResponseEntity<UpdateResponseModel> update(
            @PathVariable ID id,
            @Valid @RequestBody UpdateRequestModel request) {
        ServiceExtendedModel serviceModel = getMapper().toUpdateServiceExtendedModel(request);
        ServiceExtendedModel updated = getService().update(id, serviceModel);
        UpdateResponseModel response = getMapper().toUpdateResponse(updated);
        return ResponseEntity.ok(response);
    }

    // --- READ (paginated) ---

    @GetMapping
    @PermissionOperation("READ")
    default ResponseEntity<Page<DtoModel>> find(
            Pageable pageable,
            @RequestParam(name = "query", required = false) String query) {
        String processedQuery = addCustomQueryCondition(query);
        Page<ServiceModel> page = getService().find(pageable, processedQuery);
        Page<DtoModel> dtoPage = page.map(getMapper()::toDto);
        return ResponseEntity.ok(dtoPage);
    }

    @GetMapping("/extended")
    @PermissionOperation("READ")
    default ResponseEntity<Page<DtoExtendedModel>> findExtended(
            Pageable pageable,
            @RequestParam(name = "query", required = false) String query) {
        String processedQuery = addCustomQueryCondition(query);
        Page<ServiceExtendedModel> page = getService().findExtended(pageable, processedQuery);
        Page<DtoExtendedModel> dtoPage = page.map(getMapper()::toExtendedDto);
        return ResponseEntity.ok(dtoPage);
    }

    // --- READ (single) ---

    @GetMapping("/{id}")
    @PermissionOperation("READ")
    default ResponseEntity<DtoExtendedModel> findById(@PathVariable ID id) {
        ServiceExtendedModel model = getService().findById(id);
        DtoExtendedModel dto = getMapper().toExtendedDto(model);
        return ResponseEntity.ok(dto);
    }

    // --- COUNT ---

    @GetMapping("/count")
    @PermissionOperation("READ")
    default ResponseEntity<Long> getCount(
            @RequestParam(name = "query", required = false) String query) {
        String processedQuery = addCustomQueryCondition(query);
        long count = getService().getCount(processedQuery);
        return ResponseEntity.ok(count);
    }

    // --- AUDIT ---

    @GetMapping("/audit/{id}")
    @PermissionOperation("READ")
    default ResponseEntity<List<AuditLogEntity>> getAudit(@PathVariable ID id) {
        List<AuditLogEntity> auditRecords = getService().getAuditLogDao()
                .findByEntityClassAndEntityIdOrderByPerformedAtAsc(
                        getService().getDaoModelClass().getSimpleName(), id instanceof Long l ? l : null);
        return ResponseEntity.ok(auditRecords);
    }

    // --- DELETE ---

    @DeleteMapping("/{id}")
    @PermissionOperation("DELETE")
    default ResponseEntity<Void> deleteById(@PathVariable ID id) {
        getService().deleteById(id);
        return ResponseEntity.ok().build();
    }

    // --- SET PROPERTIES TO NULL ---

    @DeleteMapping("/{id}/property")
    @PermissionOperation("DELETE")
    default ResponseEntity<Void> setPropertiesToNull(
            @PathVariable ID id,
            @RequestParam(name = "properties") Set<String> properties) {
        getService().setPropertiesToNull(id, properties);
        return ResponseEntity.ok().build();
    }

    // --- I18N DISCOVERY ---

    @GetMapping("/i18n")
    @PermissionOperation("READ")
    default ResponseEntity<Collection<String>> getI18nProperties() {
        Collection<String> properties = getService().getMapper().getI18nSupportedProperties();
        return ResponseEntity.ok(properties);
    }

    // --- METADATA ---

    @GetMapping("/metadata")
    @PermissionOperation("READ")
    default ResponseEntity<MetadataResponse> getMetadata() {
        Class<?> daoClass = getService().getDaoModelClass();
        MetadataResponse metadata = EntityMetadataResolver.resolve(daoClass);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(metadata);
    }
}
