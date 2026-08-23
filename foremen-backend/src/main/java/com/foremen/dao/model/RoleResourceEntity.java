package com.foremen.dao.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "role_resources", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"role_id", "resource_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class RoleResourceEntity extends BaseEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", nullable = false)
    private ResourceEntity resource;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_resource_operations",
        joinColumns = @JoinColumn(name = "role_resource_id"),
        inverseJoinColumns = @JoinColumn(name = "operation_id")
    )
    private List<OperationEntity> operations = new ArrayList<>();
}
