package com.foremen.service.dashboard;

import com.foremen.dao.RoleDefaultWidgetDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardStateDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.RoleDefaultWidgetEntity;
import com.foremen.dao.model.UserDashboardStateEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * FOR-10 (Requirement 15) — copies the role's {@code role_default_widgets} into a user's placements and
 * records a {@code user_dashboard_state} row. Idempotent and serialized per user with
 * {@code pg_advisory_xact_lock(userId)} (the same key {@link DashboardLayoutService} uses).
 */
@Service
@RequiredArgsConstructor
public class DashboardDefaultsService {

    private final UserDao userDao;
    private final RoleDefaultWidgetDao roleDefaultDao;
    private final UserDashboardWidgetDao placementDao;
    private final UserDashboardStateDao stateDao;

    @PersistenceContext
    private EntityManager entityManager;

    /** Seeds defaults once; a user that already has a state row is left untouched. */
    @Transactional
    public void seedFor(Long userId) {
        if (stateDao.existsById(userId)) {
            return;
        }
        lockUser(userId);
        if (stateDao.existsById(userId)) {
            return;
        }
        Optional<UserEntity> user = userDao.findById(userId);
        if (user.isEmpty()) {
            return;
        }
        UserEntity u = user.get();
        long occupied = 0L;
        if (u.getRole() != null) {
            for (RoleDefaultWidgetEntity d : roleDefaultDao.findByRoleIdOrdered(u.getRole().getId())) {
                DashboardWidgetEntity w = d.getWidget();
                if (!Boolean.TRUE.equals(w.getActive())
                        || DashboardLayoutService.PROJECT_SCOPE_SINGLE.equals(w.getProjectScope())) {
                    continue;
                }
                int cell = d.getCellIndex();
                if (!GridMath.fits(cell, w.getColSpan(), w.getRowSpan())) {
                    continue;
                }
                long fp = GridMath.footprint(cell, w.getColSpan(), w.getRowSpan());
                if ((fp & occupied) != 0L) {
                    continue;
                }
                occupied |= fp;
                UserDashboardWidgetEntity p = new UserDashboardWidgetEntity();
                p.setUser(u);
                p.setWidget(w);
                p.setCellIndex((short) cell);
                placementDao.save(p);
            }
        }
        UserDashboardStateEntity state = new UserDashboardStateEntity();
        state.setUserId(userId);
        state.setInitializedAt(LocalDateTime.now());
        stateDao.save(state);
    }

    private void lockUser(Long userId) {
        entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:k) AS text)")
                .setParameter("k", userId)
                .getSingleResult();
    }
}
