package com.kata.conciliacion.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Un evento pendiente de publicar.
 *
 * <p>Implementa Persistable por una trampa de Spring Data: save() decide entre persist (INSERT)
 * y merge según "¿es nueva?", y por defecto lo decide mirando si el ID es null. Acá el ID (UUID)
 * lo asigna la app ANTES de guardar → Spring Data cree que la entidad ya existe → merge → hace un
 * SELECT por cada evento para buscarla, no la encuentra, y recién ahí inserta. Con Persistable le
 * decimos explícitamente que es nueva: INSERT directo, sin SELECT previo.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    // El JSON se arma afuera (DiscrepancyEventFactory); Hibernate lo guarda tal cual en jsonb.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Transient
    private boolean isNew = true;

    protected OutboxEventEntity() {
    }

    public static OutboxEventEntity pending(String aggregateType, String aggregateId, String eventType,
                                            String payload, Instant now) {
        var e = new OutboxEventEntity();
        e.id = UUID.randomUUID();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.payload = payload;
        e.createdAt = now;
        return e;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }
}
