package com.kata.conciliacion.service;

import com.kata.conciliacion.parser.ParseResult;
import com.kata.conciliacion.parser.SettlementFileParser;
import com.kata.conciliacion.processor.ResilientProcessorFileClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Punto de entrada: bytes del archivo → hash + parseo → servicio transaccional.
 *
 * <p>SIN @Transactional, a propósito. Cuando el INSERT de la run viola la clave única, Postgres
 * marca la transacción como abortada ("current transaction is aborted, commands ignored until
 * end of transaction block"): no se puede consultar nada más en ella. Por eso la violación se
 * atrapa ACÁ, afuera, cuando esa tx ya hizo rollback, y la lectura de la run ganadora corre en
 * una transacción nueva.
 */
@Component
public class ReconciliationFacade {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationFacade.class);
    static final String IDEMPOTENCY_CONSTRAINT = "uq_run_idempotency_key";
    private static final int MAX_ATTEMPTS = 3;

    private final ReconciliationService service;
    private final ResilientProcessorFileClient processorApi;
    private final SettlementFileParser parser = new SettlementFileParser(); // sin estado, sin Spring

    public ReconciliationFacade(ReconciliationService service, ResilientProcessorFileClient processorApi) {
        this.service = service;
        this.processorApi = processorApi;
    }

    /**
     * T8: el archivo viene de la API del procesador (con timeout, retry y breaker).
     *
     * <p>Dos niveles de reintento, los dos seguros por idempotencia:
     * <ul>
     *   <li>La descarga es un GET: repetirlo no cambia nada del lado del procesador.</li>
     *   <li>Si se repite la ejecución ENTERA (el scheduler reintenta, un pod muere a mitad), la
     *       clave (processor, fecha, hash) de T5 devuelve la run ya hecha en vez de duplicarla.</li>
     * </ul>
     * El download queda FUERA de toda transacción: no tener una conexión de la base tomada
     * mientras esperamos hasta 40 s a una API externa.
     */
    public RunOutcome reconcileFromProcessor(String processor, LocalDate settlementDate) {
        byte[] file = processorApi.download(processor, settlementDate);
        return reconcile(processor, settlementDate, file);
    }

    public RunOutcome reconcile(String processor, LocalDate settlementDate, byte[] fileContent) {
        String fileHash = sha256(fileContent);
        ParseResult parsed = parser.parse(new String(fileContent, StandardCharsets.UTF_8).lines());
        if (!parsed.errors().isEmpty()) {
            log.warn("File {} for {} {} has {} invalid lines", fileHash, processor, settlementDate,
                    parsed.errors().size());
        }

        // Cada intento es una llamada nueva al proxy → una tx nueva que relee el ledger.
        // Los intentos fallidos dejan su run FAILED (traza de auditoría) y no ocupan la clave.
        return OptimisticRetry.execute(MAX_ATTEMPTS, () -> {
            try {
                return service.reconcile(processor, settlementDate, fileHash, parsed.lines());
            } catch (DataIntegrityViolationException e) {
                if (!isIdempotencyKeyViolation(e)) {
                    throw e;
                }
                // Perdimos la carrera: otra ejecución ya commiteó la run con esta clave.
                log.info("Run for {} {} {} already processed concurrently", processor, settlementDate, fileHash);
                return service.findCompleted(processor, settlementDate, fileHash).orElseThrow(() -> e);
            }
        });
    }

    /** Hash de los bytes crudos: "el mismo archivo" = mismos bytes. */
    public static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest); // Java 17
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static boolean isIdempotencyKeyViolation(DataIntegrityViolationException e) {
        // El nombre del constraint viaja en el mensaje del driver (PSQLException).
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(IDEMPOTENCY_CONSTRAINT);
    }
}
