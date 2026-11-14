package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.MaterialSellerServiceMapper;

/**
 * FOR-11-00 task 11.6 - the concurrent-insert paths of {@link SellerQuickService} (Requirement 20.3 / 20.4).
 * A real race cannot be reproduced inside the rolled-back integration transaction, so the DAO is
 * scripted to throw a unique-index violation exactly like a losing concurrent insert.
 */
class SellerQuickServiceRaceTest {

    private static final String NIP = "5260250995";

    private MaterialSellerDao dao;
    private MaterialSellerServiceMapper mapper;
    private SellerQuickService service;

    @BeforeEach
    void setUp() {
        dao = mock(MaterialSellerDao.class);
        mapper = mock(MaterialSellerServiceMapper.class);
        service = new SellerQuickService(dao, mapper);
        when(mapper.toServiceModel(any(MaterialSellerEntity.class))).thenAnswer(inv -> {
            MaterialSellerEntity e = inv.getArgument(0);
            return new MaterialSellerServiceModel(e.getId(), e.getCode(), e.getNameRU(),
                    e.isActive(), e.getWebsite(), e.getNip());
        });
    }

    private static MaterialSellerEntity seller(Long id, String code, String nip) {
        MaterialSellerEntity e = new MaterialSellerEntity();
        e.setId(id);
        e.setCode(code);
        e.setNameRU("n");
        e.setNamePL("n");
        e.setActive(true);
        e.setNip(nip);
        return e;
    }

    @Test
    void duplicateNipRace_returnsTheWinnerWithoutCreating() {
        MaterialSellerEntity winner = seller(7L, "winner", NIP);
        when(dao.findByNip(NIP)).thenReturn(Optional.empty(), Optional.of(winner));
        when(dao.save(any())).thenThrow(new DataIntegrityViolationException("uq_material_sellers_nip"));

        SellerQuickService.QuickResult r = service.findOrCreate("Winner", NIP);

        assertThat(r.created()).isFalse();
        assertThat(r.seller().getId()).isEqualTo(7L);
        verify(dao, times(1)).save(any());
    }

    @Test
    void codeCollisionRace_regeneratesCodeAndRetries() {
        when(dao.existsByCode(any())).thenReturn(false, true, false);
        when(dao.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_material_sellers_code"))
                .thenAnswer(inv -> inv.getArgument(0));

        SellerQuickService.QuickResult r = service.findOrCreate("Castorama", null);

        assertThat(r.created()).isTrue();
        assertThat(r.seller().getCode()).isEqualTo("castorama_2");
        verify(dao, times(2)).save(any());
    }

    @Test
    void persistentFailure_isRethrownAfterBoundedRetries() {
        when(dao.save(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.findOrCreate("Castorama", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(dao, times(5)).save(any());
    }
}
