package com.foremen.dao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@NoRepositoryBean
public interface ReadOnlyAdminDao<DaoModel, ID> extends Repository<DaoModel, ID> {

    Page<DaoModel> findAll(Pageable pageable);

    Page<DaoModel> findAll(Specification<DaoModel> specification, Pageable pageable);

    Optional<DaoModel> findById(ID id);

    long count();

    List<DaoModel> findAllByIdIn(Collection<ID> entityIds);

    default String getViewSelectQuery() {
        return null;
    }
}
