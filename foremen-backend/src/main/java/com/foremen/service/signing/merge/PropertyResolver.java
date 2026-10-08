package com.foremen.service.signing.merge;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;

/**
 * FOR-05-08 (Requirement 3.3): resolves the property/area placeholder group — the address of works
 * and the usable area (design §Components resolver table row {@code PropertyResolver}).
 *
 * <p>The business templates reference these as the documented Bitrix-CRM field codes:
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source</th></tr>
 *   <tr><td>{@code {UfCrm1662541716442}}</td><td>address of works — the project address</td></tr>
 *   <tr><td>{@code {UfCrm1691765118573}}</td><td>usable area — the project area, falling back to the sum of the project rooms' floor areas</td></tr>
 * </table>
 *
 * <p>The works address resolves to the project's formatted address (then its plain address); the
 * usable area resolves to {@link ProjectEntity#getArea()} and, when that is absent, to the sum of
 * the rooms' {@link RoomEntity#getFloorArea()}. Each token resolves to {@code null} (unresolved)
 * when neither source yields a value — e.g. a project with no address, or with no area and no rooms
 * carrying a floor area (Requirement 3.4, null→unresolved).
 */
@Component
public class PropertyResolver implements MergeFieldResolver {

    static final String TOKEN_WORKS_ADDRESS = "UfCrm1662541716442";
    static final String TOKEN_USABLE_AREA = "UfCrm1691765118573";

    @Override
    public boolean supports(String token) {
        return TOKEN_WORKS_ADDRESS.equals(token) || TOKEN_USABLE_AREA.equals(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        return switch (token) {
            case TOKEN_WORKS_ADDRESS -> worksAddress(ctx.project());
            case TOKEN_USABLE_AREA -> usableArea(ctx);
            default -> null;
        };
    }

    private static String worksAddress(ProjectEntity project) {
        if (project == null) {
            return null;
        }
        String formatted = blankToNull(project.getFormattedAddress());
        return formatted != null ? formatted : blankToNull(project.getAddress());
    }

    private static String usableArea(MergeContext ctx) {
        ProjectEntity project = ctx.project();
        if (project != null && isPositive(project.getArea())) {
            return project.getArea().toPlainString();
        }
        BigDecimal roomsTotal = BigDecimal.ZERO;
        boolean anyRoomArea = false;
        for (RoomEntity room : ctx.rooms()) {
            BigDecimal floorArea = room == null ? null : room.getFloorArea();
            if (floorArea != null) {
                roomsTotal = roomsTotal.add(floorArea);
                anyRoomArea = true;
            }
        }
        return anyRoomArea ? roomsTotal.toPlainString() : null;
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
