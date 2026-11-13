# CLAUDE.md — Kata de conciliación (Java 21)

## Contexto

Este repo es una **kata de aprendizaje**, no un producto. Sergio es un Engineering Manager con 20+ años de experiencia que se está preparando para una entrevista técnica de **Staff Engineer (Java)** en una fintech de pagos (Finance Core / conciliación). Viene de años más enfocado en gestión y arquitectura; quiere refrescar Java moderno (8 → 21) y temas de sistemas financieros.

La especificación completa y los tickets están en `SPEC.md`.

## Cómo trabajar conmigo

- **Somos pares, no delegación.** El objetivo es que yo entienda y pueda explicar cada decisión, no tener el código listo.
- **Un ticket a la vez**, en el orden de `SPEC.md`. No adelantes trabajo de tickets siguientes.
- **Antes de codear cada ticket:** proponé un plan corto y explicá qué conceptos de Java o de diseño practica. Esperá mi OK.
- **Dejame escribir las partes clave** (las marcadas como "Practica" en el ticket). Vos armá el andamiaje, revisá lo mío y señalá errores; no reescribas todo.
- **Explicá el porqué**, sobre todo cuando haya un trade-off o una trampa (`BigDecimal.equals`, self-invocation de `@Transactional`, pinning, etc.).
- **Al cerrar cada ticket:** hacéme la pregunta "Para la entrevista" del ticket, en inglés, y dame feedback sobre mi respuesta (claridad, precisión, qué faltó).
- Si algo de lo que hago está mal o es mala práctica, decímelo directo.

## Convenciones técnicas

- Java 21, Spring Boot 3.x, Maven.
- Dominio con `record` y `sealed interface`; preferir inmutabilidad.
- El motor de matching es una función pura, sin Spring ni base de datos.
- Montos siempre en `BigDecimal`, comparados con `compareTo`.
- Tests: JUnit 5 + AssertJ; integración con Testcontainers (nada de H2).
- Migraciones con Flyway.
- Commits chicos, uno o más por ticket, con mensajes claros.

## Comandos

- Levantar Postgres: `docker compose up -d`
- Tests: `./mvnw test`
- Correr la app: `./mvnw spring-boot:run`
