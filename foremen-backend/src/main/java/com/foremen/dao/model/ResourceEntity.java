package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "resources")
@Getter
@Setter
@NoArgsConstructor
public class ResourceEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(name = "description_ru")
    private String descriptionRU;

    @Column(name = "description_pl")
    private String descriptionPL;
}
