package com.kata.conciliacion.consumer;

import com.kata.conciliacion.outbox.PublishedEvent;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumidor idempotente: abre un ticket por discrepancia, UNA vez por evento aunque el evento
 * llegue N veces (el outbox garantiza at-least-once, no exactly-once).
 *
 * <p>En la vida real vive en otro servicio, con su propia base. Está acá solo para la kata.
 *
 * <p>Las dos reglas que lo hacen correcto:
 * <ol>
 *   <li>La marca "ya procesado" y el efecto van en la MISMA transacción. Si fueran separadas:
 *       marcar → crash → el efecto nunca ocurre (evento perdido); efecto → crash → al reintentar
 *       se repite el efecto (duplicado).
 *   <li>La marca la decide un constraint (PK + ON CONFLICT), no un SELECT previo: "¿ya lo
 *       procesé?" + "proceso" tiene la misma carrera check-then-act que T5.
 * </ol>
 *
 * <p>Si el efecto fuera EXTERNO (mandar un mail, llamar a una API), no puede entrar en la tx de la
 * base: ahí el que recibe tiene que ser idempotente también (idempotency key en la request).
 */
@Component
public class DiscrepancyTicketConsumer {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyTicketConsumer.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DiscrepancyTicketConsumer(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true si lo procesó; false si era un duplicado y lo ignoró. */
    @Transactional
    public boolean handle(PublishedEvent event) {
        Timestamp now = Timestamp.from(clock.instant());

        // Reclamar el evento. Si otra entrega del mismo evento está en curso (sin commit), este
        // INSERT ESPERA; si la otra commitea → DO NOTHING → 0 filas. Mismo mecanismo que T5,
        // pero sin excepción: la tx sigue sana.
        int claimed = jdbc.update("""
                INSERT INTO processed_event (event_id, processed_at) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.id(), now);
        if (claimed == 0) {
            log.info("Duplicate event {} ({}) ignored", event.id(), event.eventType());
            return false;
        }

        // El efecto, en la misma transacción que la marca.
        jdbc.update("""
                INSERT INTO discrepancy_ticket (event_id, event_type, payload, created_at)
                VALUES (?, ?, ?::jsonb, ?)
                """, event.id(), event.eventType(), event.payload(), now);
        return true;
    }
}
