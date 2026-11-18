package com.kata.conciliacion.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.ReconciliationResult;
import com.kata.conciliacion.domain.ReconciliationResult.AmountMismatch;
import com.kata.conciliacion.domain.ReconciliationResult.Duplicate;
import com.kata.conciliacion.domain.ReconciliationResult.Matched;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInLedger;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInProcessor;
import com.kata.conciliacion.persistence.ReconciliationRunEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resultado de dominio → evento de outbox (solo para discrepancias; Matched no genera evento). */
@Component
public class DiscrepancyEventFactory {

    static final String AGGREGATE_TYPE = "ReconciliationRun";

    /**
     * El contrato público del evento. Es un record propio y NO el record de dominio: si mañana
     * cambia el dominio, el contrato con otros equipos no se rompe sin que nadie lo decida.
     */
    record DiscrepancyPayload(
            String eventType,
            long runId,
            String processor,
            LocalDate settlementDate,
            String processorReference,
            MoneyPayload ledgerAmount,
            MoneyPayload fileAmount,
            Integer duplicateCount) {
    }

    /**
     * Montos como STRING en el JSON, no como número. Un consumidor en JavaScript (o cualquiera que
     * parsee números JSON como double) convertiría 1500.10 en un double: vuelve el problema de T1.
     */
    record MoneyPayload(String amount, String currency) {
        static MoneyPayload of(Money money) {
            return new MoneyPayload(money.amount().toPlainString(), money.currency().getCurrencyCode());
        }
    }

    private final ObjectMapper objectMapper;

    public DiscrepancyEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<OutboxEventEntity> eventFor(ReconciliationResult result, ReconciliationRunEntity run,
                                                Instant now) {
        // Switch exhaustivo con record patterns: un tipo nuevo de resultado obliga a decidir acá
        // si genera evento o no.
        DiscrepancyPayload payload = switch (result) {
            case Matched m -> null;
            case AmountMismatch(var ledger, var line) ->
                    payload("AmountMismatch", run, result, MoneyPayload.of(ledger.amount()),
                            MoneyPayload.of(line.amount()), null);
            case MissingInProcessor(var ledger) ->
                    payload("MissingInProcessor", run, result, MoneyPayload.of(ledger.amount()), null, null);
            case MissingInLedger(var line) ->
                    payload("MissingInLedger", run, result, null, MoneyPayload.of(line.amount()), null);
            case Duplicate(var ref, var lines) ->
                    payload("Duplicate", run, result, null, null, lines.size());
        };
        if (payload == null) {
            return Optional.empty();
        }
        return Optional.of(OutboxEventEntity.pending(
                AGGREGATE_TYPE, String.valueOf(run.getId()), payload.eventType(), toJson(payload), now));
    }

    private static DiscrepancyPayload payload(String type, ReconciliationRunEntity run, ReconciliationResult result,
                                              MoneyPayload ledger, MoneyPayload file, Integer duplicateCount) {
        return new DiscrepancyPayload(type, run.getId(), run.getProcessor(), run.getSettlementDate(),
                result.processorReference(), ledger, file, duplicateCount);
    }

    private String toJson(DiscrepancyPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event payload", e);
        }
    }
}
