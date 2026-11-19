package com.kata.conciliacion.processor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Cliente "pelado": UNA llamada, con timeouts explícitos, que traduce el resultado HTTP a
 * éxito / transitorio / permanente. No reintenta: eso es responsabilidad del decorador.
 *
 * <p>Dos timeouts distintos:
 * <ul>
 *   <li>connectTimeout: establecer TCP(+TLS). Corto: si no conecta en 1-2 s, no va a conectar.</li>
 *   <li>request timeout: hasta recibir la respuesta. Se elige con el p99 del procesador + margen,
 *       acotado por el presupuesto total (intentos × timeout + backoffs &lt; SLA del job).</li>
 * </ul>
 * Sin timeout, el default del HttpClient es esperar para siempre: un procesador colgado
 * nos deja un thread colgado.
 */
public class ProcessorFileClient {

    private final HttpClient http;
    private final URI baseUri;
    private final Duration requestTimeout;

    public ProcessorFileClient(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = baseUri;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    public byte[] download(String processor, LocalDate settlementDate) {
        HttpRequest request = HttpRequest.newBuilder(
                        baseUri.resolve("/settlements/" + processor + "/" + settlementDate))
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) { // subclase de IOException: va primero
            throw new TransientProcessorException("Timeout after " + requestTimeout, e);
        } catch (IOException e) {
            throw new TransientProcessorException("I/O error calling processor API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // nunca tragarse la interrupción
            throw new TransientProcessorException("Interrupted", e);
        }
        return classify(response);
    }

    private static byte[] classify(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        // 429: nos están pidiendo que bajemos el ritmo → reintentable, con backoff.
        // 408: el servidor cortó esperando el request → reintentable.
        if (status >= 500 || status == 429 || status == 408) {
            throw new TransientProcessorException("Processor API returned " + status, null);
        }
        // 4xx restantes: 400 (request mal formado), 401/403 (credenciales), 404 (archivo
        // todavía no publicado). 404 se reintenta, pero más tarde desde el scheduler, no con
        // un backoff de milisegundos.
        throw new PermanentProcessorException("Processor API returned " + status, status);
    }
}
