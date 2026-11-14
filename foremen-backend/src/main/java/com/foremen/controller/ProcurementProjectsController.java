package com.foremen.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.ProcurementBuyerDto;
import com.foremen.controller.model.ProcurementProjectDto;
import com.foremen.dao.UserDao;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.access.ProcurementProjectLookupService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-11-00 (R9.8): project lookup for the procurement project filter. Not an {@code AdminController};
 * the single handler is guarded explicitly by {@code PURCHASE_POSITIONS}/READ.
 */
@RestController
@RequestMapping("/api/procurement")
@RequiredArgsConstructor
public class ProcurementProjectsController {

    private final ProcurementProjectLookupService lookupService;
    private final UserDao userDao;

    @GetMapping("/projects")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    public List<ProcurementProjectDto> projects() {
        return lookupService.projectsForCaller().stream()
                .map(p -> new ProcurementProjectDto(p.id(), p.name(), p.address(), p.status()))
                .toList();
    }

    /** FOR-11-01: active BUYER users for the request dialog (same rule as the assigned-buyer validation). */
    @GetMapping("/buyers")
    @RequiresPermission(resource = "PURCHASE_REQUESTS", operation = "READ")
    public List<ProcurementBuyerDto> buyers() {
        return userDao.findActiveByRoleCode(ProcurementProjectAccess.BUYER_ROLE_CODE).stream()
                .map(u -> new ProcurementBuyerDto(u.getId(), u.getName()))
                .toList();
    }
}
