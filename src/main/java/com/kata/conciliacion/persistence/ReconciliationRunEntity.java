package com.kata.conciliacion.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** Una ejecución de conciliación para un procesador y una fecha. */
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reconciliation_run_seq")
    @SequenceGenerator(name = "reconciliation_run_seq", sequenceName = "reconciliation_run_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String processor;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    // STRING y no ORDINAL: con ORDINAL se guarda 0/1/2 y reordenar el enum corrompe los datos.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ReconciliationRunEntity() {
    }

    public static ReconciliationRunEntity start(String processor, LocalDate settlementDate, Instant now) {
        var run = new ReconciliationRunEntity();
        run.processor = processor;
        run.settlementDate = settlementDate;
        run.status = RunStatus.RUNNING;
        run.startedAt = now;
        return run;
    }

    /** Las transiciones de estado viven en la entidad, no en setters sueltos. */
    public void complete(Instant now) {
        finish(RunStatus.COMPLETED, now);
    }

    public void fail(Instant now) {
        finish(RunStatus.FAILED, now);
    }

    private void finish(RunStatus finalStatus, Instant now) {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException("Run " + id + " is already " + status);
        }
        status = finalStatus;
        finishedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getProcessor() {
        return processor;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
