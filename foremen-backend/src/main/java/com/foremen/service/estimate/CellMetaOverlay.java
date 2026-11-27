package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;

/**
 * FOR-05-04 (design B11, Requirements 21.1 to 21.4, 21.6): the {@link ScratchMetaOverlay} the scratch
 * service runs after the replay. For each {@link CellMetaInput} it finds the scratch cell, asks
 * {@link CellFactsResolver} for the engine facts and sets {@link CellAiMeta} on the cell, so the
 * snapshot built next carries it. Facts come from the engine, texts from the input; no text is invented.
 * A cell the replay did not create is skipped.
 */
@Component
public class CellMetaOverlay implements ScratchMetaOverlay {

    /** {@code volume.source} of an override written by the job. */
    public static final String SOURCE_MODEL_OVERRIDE = "MODEL_OVERRIDE";
    /** {@code volume.source} of an override written by the chat. */
    public static final String SOURCE_CHAT_OVERRIDE = "CHAT_OVERRIDE";
    /** Reason code of an override without a reason text (Requirement 21.6). */
    public static final String MODEL_VOLUME_DIFFERS = "MODEL_VOLUME_DIFFERS";
    static final String ORIGIN_CHAT = "CHAT";
    static final int MAX_RATIONALE = 500;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CellFactsResolver factsResolver;

    public CellMetaOverlay(CellFactsResolver factsResolver) {
        this.factsResolver = factsResolver;
    }

    @Override
    public void apply(EstimateEntity estimate, Long projectId, List<CellMetaInput> inputs) {
        if (estimate == null || inputs == null) {
            return;
        }
        String packageCode = estimate.getAppliedPackageCode();
        for (CellMetaInput input : inputs) {
            if (input == null || input.workCode() == null || input.roomId() == null) {
                continue;
            }
            EstimateLineEntity line = findLine(estimate, input.workCode());
            EstimateLineRoomQtyEntity cell = line != null ? findCell(line, input.roomId()) : null;
            if (cell == null) {
                continue;
            }
            CellFactsResolver.Facts facts = factsResolver.facts(line.getWorkItem(), cell.getRoom(), packageCode);
            cell.setAiMeta(build(input, line, cell, facts));
        }
    }

    /** Builds the record of one cell from the input texts and the engine facts (pure, package-visible). */
    static CellAiMeta build(CellMetaInput input, EstimateLineEntity line, EstimateLineRoomQtyEntity cell,
                            CellFactsResolver.Facts facts) {
        CellAiMeta.Source source = input.sourceFile() != null || input.sourcePage() != null
                ? new CellAiMeta.Source(input.sourceFile(), input.sourcePage()) : null;
        return new CellAiMeta(
                input.origin(),
                input.turnId(),
                input.status(),
                input.confidence(),
                source,
                truncate(blankToNull(input.rationale())),
                volume(input, cell, facts),
                materials(cell),
                price(line, input.priceReason()));
    }

    private static CellAiMeta.Volume volume(CellMetaInput input, EstimateLineRoomQtyEntity cell,
                                            CellFactsResolver.Facts facts) {
        BigDecimal finalVolume = cell.getQuantity();
        String formulaKey = facts != null ? facts.formulaKey() : null;
        if (!cell.isVolumeOverridden()) {
            // The assign path stored the engine value: the formula volume is the cell quantity.
            String src = facts != null ? facts.source() : CellFactsResolver.SOURCE_FORMULA;
            return new CellAiMeta.Volume(src, formulaKey, finalVolume, finalVolume, null, null, null);
        }
        BigDecimal formulaVolume = facts != null ? facts.formulaVolume() : null;
        String src = ORIGIN_CHAT.equals(input.origin()) ? SOURCE_CHAT_OVERRIDE : SOURCE_MODEL_OVERRIDE;
        CellAiMeta.Correction correction = correction(formulaVolume, finalVolume);
        String reason = blankToNull(input.volumeReason());
        return new CellAiMeta.Volume(src, formulaKey, formulaVolume, finalVolume, correction, reason,
                reason == null ? MODEL_VOLUME_DIFFERS : null);
    }

    static CellAiMeta.Correction correction(BigDecimal formulaVolume, BigDecimal finalVolume) {
        if (finalVolume == null) {
            return null;
        }
        BigDecimal base = formulaVolume != null ? formulaVolume : BigDecimal.ZERO;
        BigDecimal delta = finalVolume.subtract(base);
        if (delta.signum() == 0) {
            return null;
        }
        BigDecimal pct = base.signum() == 0 ? null
                : delta.multiply(HUNDRED).divide(base, 1, RoundingMode.HALF_UP);
        return new CellAiMeta.Correction(delta.stripTrailingZeros(), pct);
    }

    private static CellAiMeta.Materials materials(EstimateLineRoomQtyEntity cell) {
        List<EstimateLineRoomMaterialEntity> lines = cell.getMaterials();
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int overridden = (int) lines.stream().filter(EstimateLineRoomMaterialEntity::isQtyOverridden).count();
        return new CellAiMeta.Materials(true, lines.size(), overridden);
    }

    /** The price block of a line whose effective labour price differs from the frozen one (R21.4). */
    static CellAiMeta.Price price(EstimateLineEntity line, String reason) {
        BigDecimal base = line.getUnitPrice();
        BigDecimal effective = line.getUnitPriceOverride();
        if (base == null || effective == null || base.compareTo(effective) == 0) {
            return null;
        }
        BigDecimal pct = base.signum() == 0 ? null
                : effective.subtract(base).multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP).stripTrailingZeros();
        return new CellAiMeta.Price(base, effective, pct, null, blankToNull(reason));
    }

    private static EstimateLineEntity findLine(EstimateEntity estimate, String workCode) {
        if (estimate.getLines() == null) {
            return null;
        }
        for (EstimateLineEntity line : estimate.getLines()) {
            if (line.getWorkItem() != null && Objects.equals(workCode, line.getWorkItem().getCode())) {
                return line;
            }
        }
        return null;
    }

    private static EstimateLineRoomQtyEntity findCell(EstimateLineEntity line, Long roomId) {
        if (line.getRoomQtys() == null) {
            return null;
        }
        for (EstimateLineRoomQtyEntity q : line.getRoomQtys()) {
            if (q.getRoom() != null && Objects.equals(roomId, q.getRoom().getId())) {
                return q;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s) {
        return s != null && s.length() > MAX_RATIONALE ? s.substring(0, MAX_RATIONALE) : s;
    }
}
