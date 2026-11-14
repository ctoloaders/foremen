package com.foremen.dao;

import com.foremen.dao.model.MaterialSellerEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialSellerDao extends AdminDao<MaterialSellerEntity, Long> {

    java.util.Optional<MaterialSellerEntity> findByNip(String nip);

    boolean existsByCode(String code);
}
