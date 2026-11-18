package com.kata.conciliacion.persistence;

import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

/**
 * Entidad JPA del ledger. NO es el record de dominio: JPA necesita constructor sin argumentos,
 * campos mutables y proxies; el dominio quiere inmutabilidad. Se traducen con toDomain/fromDomain.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_seq")
    @SequenceGenerator(name = "ledger_entry_seq", sequenceName = "ledger_entry_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String processor;

    @Column(name = "processor_reference", nullable = false)
    private String processorReference;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * Locking optimista. Hibernate agrega "AND version = ?" a cada UPDATE y lo incrementa. Si el
     * UPDATE afecta 0 filas, alguien cambió la fila desde que la leímos → excepción (no se pisa).
     * Nunca se setea a mano: es de Hibernate.
     */
    @Version
    private Long version;

    @Column(name = "reconciled_run_id")
    private Long reconciledRunId;

    protected LedgerEntryEntity() {
        // para JPA
    }

    public static LedgerEntryEntity fromDomain(LedgerEntry entry) {
        var e = new LedgerEntryEntity();
        e.processor = entry.processor();
        e.processorReference = entry.processorReference();
        e.settlementDate = entry.settlementDate();
        e.amount = entry.amount().amount();
        e.currency = entry.amount().currency().getCurrencyCode();
        return e;
    }

    public LedgerEntry toDomain() {
        // Pasa por el compact constructor de Money: lo que viene de la base se vuelve a validar.
        return new LedgerEntry(processorReference, new Money(amount, Currency.getInstance(currency)),
                processor, settlementDate);
    }

    /** La última run COMPLETED que la matcheó es la que vale. */
    public void markReconciled(Long runId) {
        this.reconciledRunId = runId;
    }

    public String getProcessorReference() {
        return processorReference;
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Long getReconciledRunId() {
        return reconciledRunId;
    }
}
