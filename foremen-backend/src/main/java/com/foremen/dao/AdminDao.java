package com.foremen.dao;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.PagingAndSortingRepository;

@NoRepositoryBean
public interface AdminDao<DaoModel, ID> extends
        ReadOnlyAdminDao<DaoModel, ID>,
        PagingAndSortingRepository<DaoModel, ID>,
        JpaSpecificationExecutor<DaoModel> {
}
