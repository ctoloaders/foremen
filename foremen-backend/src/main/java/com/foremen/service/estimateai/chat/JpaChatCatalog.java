package com.foremen.service.estimateai.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;

/** JPA-backed {@link ChatCatalog}: the same catalog data as the work catalog list, read-only. */
@Component
public class JpaChatCatalog implements ChatCatalog {

    private final WorkItemDao workItemDao;
    private final WorkCategoryDao workCategoryDao;
    private final WorkPriceDao workPriceDao;
    private final RoomDao roomDao;

    public JpaChatCatalog(WorkItemDao workItemDao, WorkCategoryDao workCategoryDao, WorkPriceDao workPriceDao,
                          RoomDao roomDao) {
        this.workItemDao = workItemDao;
        this.workCategoryDao = workCategoryDao;
        this.workPriceDao = workPriceDao;
        this.roomDao = roomDao;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkInfo> search(String query, String categoryCode, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String cat = categoryCode == null ? "" : categoryCode.trim();
        List<WorkItemEntity> hits = new ArrayList<>();
        for (WorkItemEntity w : workItemDao.findAll()) {
            if (!cat.isEmpty() && (w.getWorkCategory() == null
                    || !cat.equalsIgnoreCase(w.getWorkCategory().getCode()))) {
                continue;
            }
            if (q.isEmpty() || matches(w, q)) {
                hits.add(w);
            }
        }
        hits.sort(Comparator
                .comparing((WorkItemEntity w) -> !w.isActive())
                .thenComparing(w -> rank(w, q))
                .thenComparing(WorkItemEntity::getCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return toInfos(hits.subList(0, Math.min(hits.size(), Math.max(1, limit))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkInfo> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.trim();
        return StreamSupport.stream(workItemDao.findAll().spliterator(), false)
                .filter(w -> w.getCode() != null && w.getCode().equalsIgnoreCase(c))
                .findFirst()
                .map(w -> toInfos(List.of(w)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkInfo> findById(Long id) {
        return id == null ? Optional.empty()
                : workItemDao.findById(id).map(w -> toInfos(List.of(w)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> categoryIdByCode(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return Optional.empty();
        }
        String c = categoryCode.trim();
        for (WorkCategoryEntity cat : workCategoryDao.findAll()) {
            if (cat.getCode() != null && cat.getCode().equalsIgnoreCase(c)) {
                return Optional.of(cat.getId());
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> workCodes(Collection<Long> workItemIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (workItemIds == null || workItemIds.isEmpty()) {
            return out;
        }
        for (WorkItemEntity w : workItemDao.findAllById(new HashSet<>(workItemIds))) {
            out.put(w.getId(), w.getCode());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomInfo> projectRooms(Long projectId) {
        List<RoomInfo> out = new ArrayList<>();
        for (RoomEntity r : roomDao.findByProjectId(projectId)) {
            RoomTypeEntity type = r.getRoomType();
            out.add(new RoomInfo(r.getId(), r.getLabel(), type == null ? null : type.getId()));
        }
        return out;
    }

    private List<WorkInfo> toInfos(List<WorkItemEntity> items) {
        Set<Long> priced = new HashSet<>();
        if (!items.isEmpty()) {
            List<Long> ids = items.stream().map(WorkItemEntity::getId).toList();
            for (WorkPriceEntity p : workPriceDao.findByWorkItemIdIn(ids)) {
                if (p.getNetPrice() != null && p.getNetPrice().signum() > 0) {
                    priced.add(p.getWorkItem().getId());
                }
            }
        }
        List<WorkInfo> out = new ArrayList<>();
        for (WorkItemEntity w : items) {
            WorkCategoryEntity c = w.getWorkCategory();
            out.add(new WorkInfo(w.getId(), w.getCode(), w.getNameRU(), w.getNamePL(),
                    w.getUnit() == null ? null : w.getUnit().getCode(),
                    w.isActive() && (c == null || c.isActive()), priced.contains(w.getId()),
                    c == null ? null : c.getId(), c == null ? null : c.getCode(),
                    w.getRoomTypes().stream().map(RoomTypeEntity::getId).collect(Collectors.toSet())));
        }
        return out;
    }

    private static boolean matches(WorkItemEntity w, String q) {
        return contains(w.getCode(), q) || contains(w.getNameRU(), q) || contains(w.getNamePL(), q);
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    private static int rank(WorkItemEntity w, String q) {
        if (q.isEmpty() || w.getCode() == null) {
            return 2;
        }
        String code = w.getCode().toLowerCase(Locale.ROOT);
        return code.equals(q) ? 0 : code.startsWith(q) ? 1 : 2;
    }
}
