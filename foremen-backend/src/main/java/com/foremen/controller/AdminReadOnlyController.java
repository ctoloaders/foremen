package com.foremen.controller;

import com.foremen.controller.model.MetadataResponse;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.util.EntityMetadataResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

public interface AdminReadOnlyController<ServiceModel, ServiceExtendedModel, DaoModel, ID> {

    ReadOnlyAdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID> getService();

    @GetMapping
    default ResponseEntity<Page<ServiceModel>> find(
            Pageable pageable,
            @RequestParam(name = "query", required = false) String query) {
        Page<ServiceModel> page = getService().find(pageable, query);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    default ResponseEntity<ServiceModel> findById(@PathVariable ID id) {
        ServiceModel model = getService().findByIdLocalized(id);
        return ResponseEntity.ok(model);
    }

    @GetMapping("/metadata")
    default ResponseEntity<MetadataResponse> getMetadata() {
        Class<?> daoClass = getService().getDaoModelClass();
        MetadataResponse metadata = EntityMetadataResolver.resolve(daoClass);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(metadata);
    }
}
