package com.kata.conciliacion.config;

import com.kata.conciliacion.engine.ReconciliationEngine;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El motor no lleva @Component: es Java puro y no sabe que Spring existe. Spring lo registra
 * desde afuera, con un @Bean.
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    @Bean
    ReconciliationEngine reconciliationEngine() {
        return new ReconciliationEngine();
    }

    /** Clock inyectable: en tests se reemplaza por Clock.fixed y las fechas son deterministas. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
