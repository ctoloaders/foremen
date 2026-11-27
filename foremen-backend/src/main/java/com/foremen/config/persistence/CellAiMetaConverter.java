package com.foremen.config.persistence;

import java.sql.SQLException;

import org.postgresql.util.PGobject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.estimate.CellAiMeta;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * FOR-05-04 (design B11): JPA converter {@link CellAiMeta} &harr; PostgreSQL {@code jsonb}, in the
 * style of {@link JsonMapConverter}. {@code null} maps to SQL NULL (manual cell).
 */
@Converter(autoApply = false)
public class CellAiMetaConverter implements AttributeConverter<CellAiMeta, Object> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object convertToDatabaseColumn(CellAiMeta attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(MAPPER.writeValueAsString(attribute));
            return pg;
        } catch (JsonProcessingException | SQLException e) {
            throw new IllegalArgumentException("Cannot serialize ai_meta", e);
        }
    }

    @Override
    public CellAiMeta convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }
        String json = dbData instanceof PGobject pg ? pg.getValue() : dbData.toString();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, CellAiMeta.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize ai_meta", e);
        }
    }
}
