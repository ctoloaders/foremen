package com.foremen.mapper.fixture;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TestCreateRequest {
    private String nameRU;
    private String namePL;
    private String descriptionRU;
    private String descriptionPL;
    private String code;
    private Integer quantity;
}
