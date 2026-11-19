package com.kata.conciliacion.config;

import com.kata.conciliacion.processor.ProcessorFileClient;
import com.kata.conciliacion.processor.ResilientProcessorFileClient;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProcessorApiConfig {

    @Bean
    ResilientProcessorFileClient processorFileClient(
            @Value("${conciliacion.processor-api.base-url}") URI baseUrl,
            @Value("${conciliacion.processor-api.connect-timeout}") Duration connectTimeout,
            @Value("${conciliacion.processor-api.request-timeout}") Duration requestTimeout) {
        return new ResilientProcessorFileClient(
                new ProcessorFileClient(baseUrl, connectTimeout, requestTimeout),
                ResilientProcessorFileClient.Settings.defaults());
    }
}
