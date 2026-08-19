package com.foremen.service.integration.dao;

import com.foremen.dao.AdminDao;
import com.foremen.service.integration.entity.SampleEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface SampleEntityDao extends AdminDao<SampleEntity, Long> {
}
