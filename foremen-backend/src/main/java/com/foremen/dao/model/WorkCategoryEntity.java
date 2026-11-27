package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "work_categories")
@Getter
@Setter
@NoArgsConstructor
public class WorkCategoryEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(nullable = false)
    private boolean active = true;

    /** FOR-05-04 (design B1): optional AI calculation rules; trimmed, blank stored as null. */
    @Column(name = "ai_instructions", columnDefinition = "text")
    private String aiInstructions;
}
