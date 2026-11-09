package com.foremen.dao.model;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FOR-10 (task 1.1) — composite key {@code (role_id, cell_index)} of {@link RoleDefaultWidgetEntity}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RoleDefaultWidgetId implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long roleId;
    private Short cellIndex;
}
