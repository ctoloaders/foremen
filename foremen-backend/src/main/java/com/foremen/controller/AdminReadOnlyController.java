package com.foremen.controller;

import com.foremen.service.ReadOnlyAdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
