package com.kata.conciliacion;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * El contenedor es un bean del contexto de Spring. Todos los tests de integración importan esta
 * misma configuración → comparten el contexto cacheado y, con él, un único Postgres.
 *
 * <p>Alternativa descartada: {@code @Container static} en cada clase de test. JUnit arranca y
 * apaga un contenedor por clase, cada clase arma su propio contexto (más lento), y los contextos
 * que Spring dejó en cache quedan apuntando a contenedores ya apagados.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection // Spring Boot toma URL/usuario/password del contenedor; no hace falta @DynamicPropertySource
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
