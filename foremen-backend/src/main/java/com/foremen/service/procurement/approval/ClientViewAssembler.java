package com.foremen.service.procurement.approval;

import com.foremen.controller.model.client.ClientApprovalPositionDto;
import com.foremen.controller.model.client.ClientItemDto;
import com.foremen.controller.model.client.ClientMoneyDto;
import com.foremen.controller.model.client.ClientOptionDto;
import com.foremen.controller.model.client.ClientRoundDto;
import com.foremen.service.procurement.position.PositionMoney;
import com.foremen.service.procurement.position.PositionPriceVisibility;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * FOR-11-02 (design 4): the only builder of client DTOs. It works exclusively from the narrowed
 * {@link ClientApprovalData} projections, so no purchase-side value is reachable from here.
 */
@Component
public class ClientViewAssembler {

    public List<ClientApprovalPositionDto> assemble(ClientApprovalData data, Locale locale, LocalDate today) {
        List<ClientApprovalPositionDto> result = new ArrayList<>();
        for (ClientPositionSource p : data.positions()) {
            List<ClientRoundSource> rounds = data.roundsByPosition().getOrDefault(p.positionId(), List.of());
            result.add(assemble(p, rounds, data.optionsByRound(), locale, today));
        }
        return result;
    }

    public ClientApprovalPositionDto assemble(ClientPositionSource p, List<ClientRoundSource> rounds,
                                              Map<Long, List<ClientOptionSource>> optionsByRound,
                                              Locale locale, LocalDate today) {
        boolean pl = isPolish(locale);
        ClientRoundSource open = null;
        List<ClientRoundDto> suggestions = new ArrayList<>();
        List<ClientRoundDto> history = new ArrayList<>();
        for (ClientRoundSource r : rounds) {
            boolean isOpen = r.status() == ApprovalRoundStatus.OPEN;
            if (isOpen && r.kind() != ApprovalRoundKind.CLIENT_SUGGESTION && open == null) {
                open = r;
            } else if (isOpen && r.kind() == ApprovalRoundKind.CLIENT_SUGGESTION) {
                suggestions.add(round(r, p, optionsByRound, pl));
            } else {
                history.add(round(r, p, optionsByRound, pl));
            }
        }
        PositionMoney visible = PositionPriceVisibility.apply(Audience.CLIENT, p.sourcingPath(), p.status(),
                new PositionMoney(null, null, null, p.salePrice(), null, null));
        ClientMoneyDto money = money(visible.salePrice(), p.saleVatRatePercent(), p.quantity());
        ApprovalRoundKind openKind = open == null ? null : open.kind();
        LocalDate deadline = open != null && open.decisionDeadline() != null
                ? open.decisionDeadline() : p.decisionDeadline();
        boolean overdue = open != null
                && ApprovalDeadlinePolicy.isOverdue(open.status(), open.decisionDeadline(), today);
        Set<ClientAction> actions = ApprovalActionRules.clientActions(Audience.CLIENT, p.sourcingPath(),
                p.status(), openKind);
        return new ClientApprovalPositionDto(p.positionId(), p.blockNo(),
                item(p.materialId(), p.model(), p.sku(), p.producerNameRu(), p.producerNamePl(), p.link(),
                        p.photo(), pl),
                p.quantity(), pl ? p.unitNamePl() : p.unitNameRu(),
                ClientStatusMapper.map(p.status(), p.sourcingPath(), open != null),
                p.sourcingPath(), money, deadline, overdue,
                open == null ? null : round(open, p, optionsByRound, pl),
                suggestions, history, actions);
    }

    private ClientRoundDto round(ClientRoundSource r, ClientPositionSource p,
                                 Map<Long, List<ClientOptionSource>> optionsByRound, boolean pl) {
        List<ClientOptionDto> options = new ArrayList<>();
        for (ClientOptionSource o : optionsByRound.getOrDefault(r.roundId(), List.of())) {
            ClientMoneyDto money = r.kind() == ApprovalRoundKind.PACKAGE_CHOICE
                    ? null : money(o.unitSalePrice(), p.saleVatRatePercent(), p.quantity());
            options.add(new ClientOptionDto(o.optionId(),
                    item(o.materialId(), o.model(), o.sku(), o.producerNameRu(), o.producerNamePl(), o.link(),
                            o.photo(), pl),
                    money));
        }
        return new ClientRoundDto(r.roundId(), r.roundNo(), r.kind(), r.status(), r.decisionDeadline(),
                r.managerNote(), r.suggestionLink(), r.decisionComment(), r.decidedAt(), r.decidedSide(),
                r.onBehalf(), options, r.chosenOptionId());
    }

    private static ClientItemDto item(Long materialId, String model, String sku, String producerRu,
                                      String producerPl, String link, String photo, boolean pl) {
        return new ClientItemDto(materialId, model, sku, pl ? producerPl : producerRu, link, photo);
    }

    private static ClientMoneyDto money(BigDecimal unitNet, BigDecimal vat, BigDecimal quantity) {
        ClientMoneyCalculator.Money m = ClientMoneyCalculator.calculate(unitNet, vat, quantity);
        return m == null ? null
                : new ClientMoneyDto(m.unitNet(), m.vatRatePercent(), m.unitGross(), m.lineNet(), m.lineGross());
    }

    private static boolean isPolish(Locale locale) {
        return locale != null && "pl".equalsIgnoreCase(locale.getLanguage());
    }
}
