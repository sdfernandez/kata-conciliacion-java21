package com.kata.conciliacion.processor;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API del procesador simulada con WireMock: sin Spring, sin base, corre en ~2 s. */
@WireMockTest
class ResilientProcessorFileClientTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 18);
    private static final String PATH = "/settlements/ACME/2026-11-18";
    private static final byte[] FILE = "payment_id,amount,currency\n".getBytes();

    private URI baseUri;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        baseUri = URI.create(wm.getHttpBaseUrl());
    }

    private ResilientProcessorFileClient client(int maxAttempts, int windowSize, Duration openDuration,
                                                Duration requestTimeout) {
        return new ResilientProcessorFileClient(
                new ProcessorFileClient(baseUri, Duration.ofSeconds(1), requestTimeout),
                new ResilientProcessorFileClient.Settings(maxAttempts, Duration.ofMillis(10),
                        Duration.ofMillis(200), windowSize, openDuration));
    }

    private ResilientProcessorFileClient client(int maxAttempts) {
        return client(maxAttempts, 100, Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    @Test
    void retriesTransientErrorsUntilSuccess() {
        // Escenario con estado: 503 → 503 → 200.
        stubFor(get(PATH).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        stubFor(get(PATH).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        stubFor(get(PATH).inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withBody(FILE)));

        byte[] body = client(4).download("ACME", DATE);

        assertThat(body).isEqualTo(FILE);
        verify(3, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void doesNotRetryPermanentErrors() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(400)));
        ResilientProcessorFileClient client = client(4);

        assertThatThrownBy(() -> client.download("ACME", DATE))
                .isInstanceOf(PermanentProcessorException.class);

        verify(1, getRequestedFor(urlEqualTo(PATH))); // un solo request: reintentar un 400 es pura carga
    }

    @Test
    void slowResponseTimesOutAndIsRetried() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(200).withBody(FILE).withFixedDelay(500)));
        ResilientProcessorFileClient client = client(2, 100, Duration.ofSeconds(30), Duration.ofMillis(100));

        assertThatThrownBy(() -> client.download("ACME", DATE))
                .isInstanceOf(TransientProcessorException.class)
                .hasMessageContaining("Timeout");

        // WireMock registra el request recién al terminar de responder (500 ms), después de
        // que el cliente ya cortó: esperamos a que aparezca en el journal.
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> verify(2, getRequestedFor(urlEqualTo(PATH))));
    }

    @Test
    void openCircuitStopsCallingTheProcessor() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(503)));
        // 2 intentos por llamada, ventana de 4 → se abre después de 2 llamadas (4 fallas).
        ResilientProcessorFileClient client = client(2, 4, Duration.ofSeconds(30), Duration.ofSeconds(1));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.download("ACME", DATE))
                    .isInstanceOf(TransientProcessorException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Breaker abierto: falla al instante, sin tocar la red y SIN que el retry lo reintente.
        assertThatThrownBy(() -> client.download("ACME", DATE))
                .isInstanceOf(CallNotPermittedException.class);
        verify(4, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void permanentErrorsDoNotOpenTheCircuit() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(404)));
        ResilientProcessorFileClient client = client(1, 4, Duration.ofSeconds(30), Duration.ofSeconds(1));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.download("ACME", DATE))
                    .isInstanceOf(PermanentProcessorException.class);
        }

        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenProbesCloseTheCircuitWhenTheProcessorRecovers() throws InterruptedException {
        stubFor(get(PATH).willReturn(aResponse().withStatus(503)));
        ResilientProcessorFileClient client = client(1, 4, Duration.ofMillis(200), Duration.ofSeconds(1));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.download("ACME", DATE));
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        WireMock.reset();
        stubFor(get(PATH).willReturn(aResponse().withStatus(200).withBody(FILE)));
        Thread.sleep(250); // pasa el waitDurationInOpenState

        // 2 llamadas de prueba en HALF_OPEN salen bien → vuelve a CLOSED.
        assertThat(client.download("ACME", DATE)).isEqualTo(FILE);
        assertThat(client.download("ACME", DATE)).isEqualTo(FILE);
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void backoffGrowsExponentiallyWithJitter() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(503)));
        ResilientProcessorFileClient client = client(4); // base 10 ms, ×2, ±50%
        List<Long> firstWaits = new ArrayList<>();
        List<Long> thirdWaits = new ArrayList<>();
        client.retry().getEventPublisher().onRetry(e -> {
            long ms = e.getWaitInterval().toMillis();
            if (e.getNumberOfRetryAttempts() == 1) firstWaits.add(ms);
            if (e.getNumberOfRetryAttempts() == 3) thirdWaits.add(ms);
        });

        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> client.download("ACME", DATE));
        }

        // 1er reintento ≈ 10 ms ±50%; 3er reintento ≈ 40 ms ±50%.
        assertThat(firstWaits).allSatisfy(ms -> assertThat(ms).isBetween(5L, 15L));
        assertThat(thirdWaits).allSatisfy(ms -> assertThat(ms).isBetween(20L, 60L));
        // Jitter: los clientes no reintentan todos en el mismo instante.
        assertThat(thirdWaits).hasSize(8);
        assertThat(thirdWaits.stream().distinct().count()).isGreaterThan(1);
    }
}
