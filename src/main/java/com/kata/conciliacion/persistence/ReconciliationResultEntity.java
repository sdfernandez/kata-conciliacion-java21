package com.kata.conciliacion.persistence;

import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.ReconciliationResult;
import com.kata.conciliacion.domain.ReconciliationResult.AmountMismatch;
import com.kata.conciliacion.domain.ReconciliationResult.Duplicate;
import com.kata.conciliacion.domain.ReconciliationResult.Matched;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInLedger;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInProcessor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un resultado persistido. Una sola tabla para los 5 tipos (single table): las columnas que no
 * aplican quedan en null. Alternativa descartada: herencia JPA (@Inheritance) con una entidad por
 * tipo; agrega joins o discriminadores mágicos y no aporta, porque el comportamiento ya vive en
 * la sealed interface del dominio.
 */
@Entity
@Table(name = "reconciliation_result")
public class ReconciliationResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reconciliation_result_seq")
    @SequenceGenerator(name = "reconciliation_result_seq", sequenceName = "reconciliation_result_seq",
            allocationSize = 50)
    private Long id;

    // LAZY explícito: @ManyToOne es EAGER por defecto en JPA (al revés que @OneToMany).
    // EAGER no evita el N+1: lo esconde, y lo dispara en cada query aunque no uses la run.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ReconciliationRunEntity run;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "processor_reference", nullable = false)
    private String processorReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false)
    private ResultType resultType;

    @Column(name = "ledger_amount", precision = 19, scale = 2)
    private BigDecimal ledgerAmount;

    @Column(name = "ledger_currency", length = 3)
    private String ledgerCurrency;

    @Column(name = "file_amount", precision = 19, scale = 2)
    private BigDecimal fileAmount;

    @Column(name = "file_currency", length = 3)
    private String fileCurrency;

    @Column(name = "duplicate_count")
    private Integer duplicateCount;

    protected ReconciliationResultEntity() {
    }

    /**
     * Dominio → fila. Switch exhaustivo con record patterns (Java 21): si mañana se agrega un tipo
     * a ReconciliationResult, este método deja de compilar en vez de guardar basura en silencio.
     */
    public static ReconciliationResultEntity of(ReconciliationResult result, ReconciliationRunEntity run) {
        var e = new ReconciliationResultEntity();
        e.run = run;
        e.settlementDate = run.getSettlementDate();
        e.processorReference = result.processorReference();

        switch (result) {
            case Matched(var ledger, var line) -> {
                e.resultType = ResultType.MATCHED;
                e.setLedger(ledger.amount());
                e.setFile(line.amount());
            }
            case AmountMismatch(var ledger, var line) -> {
                e.resultType = ResultType.AMOUNT_MISMATCH;
                e.setLedger(ledger.amount());
                e.setFile(line.amount());
            }
            case MissingInProcessor(var ledger) -> {
                e.resultType = ResultType.MISSING_IN_PROCESSOR;
                e.setLedger(ledger.amount());
            }
            case MissingInLedger(var line) -> {
                e.resultType = ResultType.MISSING_IN_LEDGER;
                e.setFile(line.amount());
            }
            case Duplicate(var ref, var lines) -> {
                e.resultType = ResultType.DUPLICATE;
                e.duplicateCount = lines.size();
            }
        }
        return e;
    }

    private void setLedger(Money money) {
        ledgerAmount = money.amount();
        ledgerCurrency = money.currency().getCurrencyCode();
    }

    private void setFile(Money money) {
        fileAmount = money.amount();
        fileCurrency = money.currency().getCurrencyCode();
    }

    public Long getId() {
        return id;
    }

    public ReconciliationRunEntity getRun() {
        return run;
    }

    public String getProcessorReference() {
        return processorReference;
    }

    public ResultType getResultType() {
        return resultType;
    }

    public BigDecimal getLedgerAmount() {
        return ledgerAmount;
    }

    public BigDecimal getFileAmount() {
        return fileAmount;
    }

    public Integer getDuplicateCount() {
        return duplicateCount;
    }
}
