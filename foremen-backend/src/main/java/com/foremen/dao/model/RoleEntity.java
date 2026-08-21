package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class RoleEntity extends BaseEntity {

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

    @Column(nullable = false)
    private boolean system = false;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RoleResourceEntity> roleResources = new ArrayList<>();
}
