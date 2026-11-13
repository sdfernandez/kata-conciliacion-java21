# Kata: servicio de conciliación de pagos (Java 21)

## Objetivo

Practicar Java moderno (8 → 21) y los temas que pesan en un sistema financiero —corrección, idempotencia, concurrencia y resiliencia— construyendo un servicio chico de conciliación, parecido a lo que hace Finance Core en un procesador de pagos.

No es un producto: es un ejercicio. Lo que importa es entender cada decisión y poder explicarla en una entrevista.

## El problema

Una fintech registra cada pago en su **ledger interno**. Cada día, cada **procesador** (por ejemplo, "PROC_BR", "PROC_MX") envía un **archivo de liquidación** (CSV) con lo que efectivamente procesó. Hay que comparar las dos fuentes y clasificar cada pago:

| Resultado | Significado |
|---|---|
| `Matched` | Está en las dos fuentes, mismo monto y moneda |
| `MissingInProcessor` | Está en el ledger pero no en el archivo |
| `MissingInLedger` | Está en el archivo pero no en el ledger |
| `AmountMismatch` | Está en las dos, pero el monto o la moneda no coinciden |
| `Duplicate` | La misma referencia aparece más de una vez en el archivo |

La clave de matching es la **referencia del procesador** (`processorReference`).

### Formato del archivo

```csv
processor_reference,amount,currency,settled_at
PR-0001,1500.00,BRL,2026-11-12T10:15:00Z
PR-0002,99.90,MXN,2026-11-12T11:02:00Z
```

### Reglas de negocio

- Los montos se comparan con `BigDecimal.compareTo`, nunca con `equals` (`1500.0` y `1500.00` son el mismo monto).
- Moneda distinta = `AmountMismatch`.
- Reprocesar el **mismo archivo** no debe generar resultados ni eventos duplicados (idempotencia).
- Dos ejecuciones concurrentes del mismo archivo: solo una debe procesarlo.
- Cada discrepancia genera un evento, publicado con garantía at-least-once (patrón outbox).

## Stack

- Java 21, Spring Boot 3.x, Maven
- PostgreSQL (docker compose) + Flyway
- Spring Data JPA / Hibernate
- JUnit 5, AssertJ, Testcontainers
- Resilience4j (ticket 8)
- Micrometer (ticket 9, opcional)

Sin Kafka: el publisher del outbox escribe en log. Lo importante es el patrón, no el broker.

## Tickets

Prioridad para antes del jueves 19/11: **T0 a T5 y T8**. T6, T7 y T9 si da el tiempo.

Cada ticket tiene **"Para la entrevista"**: una pregunta que tenés que poder responder en voz alta, en inglés, al terminarlo.

---

### T0 — Esqueleto
- Proyecto Spring Boot 3 con Java 21 y Maven.
- `docker-compose.yml` con Postgres; Flyway con una migración inicial vacía.
- Un test de integración con Testcontainers que levanta el contexto y responde `/actuator/health`.

**Practica:** setup moderno, Testcontainers.
**Para la entrevista:** ¿Por qué Testcontainers y no H2 para tests de integración?

---

### T1 — Modelo de dominio
- `record Money(BigDecimal amount, Currency currency)` con validación en el compact constructor (no nulos, escala ≤ 2, normalizada).
- `record LedgerEntry(...)` y `record SettlementLine(...)`.
- `sealed interface ReconciliationResult permits Matched, MissingInProcessor, MissingInLedger, AmountMismatch, Duplicate`, cada uno un record.
- Tests de `Money`: igualdad con distinta escala, monedas distintas, valores inválidos.

**Practica:** records, compact constructors, sealed interfaces (17), trampas de `BigDecimal`.
**Para la entrevista:** ¿Por qué `BigDecimal` y no `double` para plata? ¿Qué problema tiene `BigDecimal.equals`? ¿Qué te da `sealed` que no te da una interfaz común?

---

### T2 — Parser del archivo
- Leer el CSV con `Files.lines` y streams; devolver `List<SettlementLine>`.
- Detectar duplicados con `Collectors.groupingBy` (o `toMap` con merge function).
- Líneas inválidas: no tirar todo el archivo; juntarlas en un resultado de errores.
- Fixtures de test con **text blocks**.

**Practica:** streams y collectors (8), Optional, text blocks (15), manejo de errores por línea.
**Para la entrevista:** ¿Cómo procesarías un archivo de 5 GB sin cargarlo entero en memoria?

---

### T3 — Motor de matching (función pura)
- `ReconciliationEngine.reconcile(List<LedgerEntry>, List<SettlementLine>) → List<ReconciliationResult>`.
- Sin base de datos ni efectos colaterales: entra data, sale data.
- Un resumen por tipo usando **pattern matching en switch** sobre la sealed interface (que el compilador avise si falta un caso).
- Tests de cada caso, incluido el caso borde de duplicado + mismatch.

**Practica:** pattern matching para switch y record patterns (21), exhaustividad, diseño funcional.
**Para la entrevista:** ¿Por qué conviene que el motor sea una función pura? ¿Cómo lo testeás?

---

### T4 — Persistencia
- Tablas: `ledger_entry`, `reconciliation_run`, `reconciliation_result`. Índices donde haga falta.
- Servicio `@Transactional` que carga el ledger del día y del procesador, corre el motor y guarda los resultados.
- Evitar el problema N+1 (fetch join, batch size o una query proyectada).
- Probar a propósito un caso de self-invocation de `@Transactional` y ver que no abre transacción.

**Practica:** JPA/Hibernate, propagation, rollback rules (unchecked vs checked), N+1, proxies de Spring.
**Para la entrevista:** ¿Por qué `@Transactional` no funciona cuando un método llama a otro de la misma clase? ¿Cuándo usarías `REQUIRES_NEW`?

---

### T5 — Idempotencia y concurrencia
- Clave de ejecución: `processor + settlement_date + sha256(archivo)` con **unique constraint** en `reconciliation_run`.
- Si la clave ya existe, devolver el resultado anterior sin reprocesar.
- Test con dos hilos procesando el mismo archivo a la vez: una sola ejecución gana; la otra recibe el resultado existente.
- `@Version` (locking optimista) al marcar entradas del ledger como conciliadas; test que provoque y maneje `OptimisticLockException`.

**Practica:** idempotencia, constraints como última línea de defensa, locking optimista vs pesimista, niveles de aislamiento.
**Para la entrevista:** ¿Optimista o pesimista para este caso, y por qué? ¿Por qué no alcanza con chequear "si existe" antes de insertar?

---

### T6 — Outbox
- Tabla `outbox_event`. Cada discrepancia se guarda junto con su evento **en la misma transacción**.
- Un publisher con `@Scheduled` toma eventos pendientes con `SELECT ... FOR UPDATE SKIP LOCKED`, los "publica" (log) y los marca enviados.
- Consumidor idempotente: si el mismo evento llega dos veces, se procesa una sola vez.

**Practica:** outbox pattern, at-least-once, dual write problem, `SKIP LOCKED`.
**Para la entrevista:** ¿Qué es el dual write problem y cómo lo resuelve el outbox? ¿Exactly-once existe?

---

### T7 — Virtual threads
- Procesar archivos de varios procesadores en paralelo con `Executors.newVirtualThreadPerTaskExecutor()`.
- Comparar con un pool de platform threads (tiempo y cantidad de hilos).
- Provocar **pinning** con un bloque `synchronized` que hace I/O y detectarlo con JFR (evento `jdk.VirtualThreadPinned`); reemplazar por `ReentrantLock`.
- Activar `spring.threads.virtual.enabled=true` y ver qué cambia.

**Practica:** virtual threads (21), pinning, cuándo NO usarlos (tareas CPU-bound), structured concurrency (preview, solo leer).
**Para la entrevista:** ¿Cuándo usarías virtual threads y cuándo no? ¿Qué es el pinning? (Dato: en Java 24 se corrigió el pinning con `synchronized`.)

---

### T8 — Resiliencia
- El archivo ya no se lee de disco: se descarga de una "API del procesador" (stub con WireMock que a veces tarda o falla).
- Timeouts explícitos, retry con backoff exponencial y jitter, circuit breaker (Resilience4j).
- Decidir qué errores se reintentan y cuáles no.

**Practica:** resiliencia de microservicios en producción (el tema de la charla de César Ponce).
**Para la entrevista:** ¿Por qué el retry necesita idempotencia? ¿Cómo elegís el timeout? ¿Qué pasa si reintentás sin jitter?

---

### T9 — Observabilidad (opcional)
- Métricas con Micrometer: resultados por tipo, duración de cada ejecución, eventos pendientes en el outbox.
- Logs estructurados con `runId` en el MDC.

**Para la entrevista:** ¿Qué alertas pondrías sobre este servicio?

---

## Repaso de Java 8 → 21 (fuera de la kata)

1. **Java 8:** lambdas, streams, Optional, CompletableFuture, java.time.
2. **Java 9–11:** módulos (solo concepto), `var`, `List.of`, nuevo HttpClient.
3. **Java 14–17:** records, text blocks, switch expressions, sealed classes, pattern matching para `instanceof`.
4. **Java 21:** virtual threads, pattern matching para switch, record patterns, sequenced collections.
5. **JVM:** Java Memory Model y happens-before, `volatile`, G1 vs ZGC, classloading (jerarquía, delegación, problemas típicos en app servers).
