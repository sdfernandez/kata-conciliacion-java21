package com.kata.conciliacion.enrichment;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pools separados por criticidad (bulkhead = compartimentos estancos de un barco): si se inunda
 * uno, el otro sigue seco. Una dependencia lenta del enriquecimiento no puede dejar sin hilos a la
 * conciliación.
 */
public final class Bulkheads {

    private Bulkheads() {
    }

    /**
     * Conciliación (CRÍTICO). Habla con la base: no sirve tener más hilos que conexiones en el pool
     * de Hikari (los de más esperarían una conexión). CallerRunsPolicy: si la cola se llena, la
     * tarea la ejecuta el hilo que la envió → el productor se frena solo (backpressure) y no se
     * pierde trabajo crítico.
     */
    public static ThreadPoolExecutor critical(int dbConnections, int queueCapacity) {
        return boundedPool("critical-", dbConnections, queueCapacity, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Enriquecimiento/notificaciones (NO CRÍTICO). IO-bound → más hilos que cores. AbortPolicy: si
     * está saturado, RECHAZA enseguida (RejectedExecutionException) y el llamador degrada. Mejor
     * un ticket sin enriquecer que una cola infinita de trabajo viejo.
     */
    public static ThreadPoolExecutor nonCritical(int threads, int queueCapacity) {
        return boundedPool("enrichment-", threads, queueCapacity, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * ThreadPoolExecutor explícito y no Executors.newFixedThreadPool(n): ese usa una
     * LinkedBlockingQueue SIN LÍMITE. Ante una dependencia lenta, la cola crece sin freno →
     * latencia infinita (tareas que esperan minutos) y, al final, OutOfMemoryError. Nunca rechaza.
     *
     * <p>Otra trampa: con core < max y cola sin límite, el pool NUNCA crece por encima de core: solo
     * crea hilos extra cuando la cola está llena, y una cola sin límite nunca se llena.
     */
    static ThreadPoolExecutor boundedPool(String namePrefix, int threads, int queueCapacity,
                                          RejectedExecutionHandler onSaturation) {
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name(namePrefix, 0).factory(), // Java 21: nombres → thread dumps legibles
                onSaturation);
    }

    /** Fórmulas de dimensionamiento (Goetz, "Java Concurrency in Practice"). */
    public static final class Sizing {

        private Sizing() {
        }

        /** CPU-bound: un hilo por core. Más hilos solo agregan context switches. */
        public static int cpuBound(int cores) {
            return cores;
        }

        /**
         * IO-bound: cores × (1 + espera/cómputo). Mientras un hilo espera la red, el core puede
         * atender a otros. Ej.: 12 cores, 100 ms esperando y 5 ms de CPU → 12 × 21 = 252 hilos.
         *
         * <p>Es un TECHO teórico de CPU. El límite real suele ser otro recurso: conexiones a la base,
         * rate limit del servicio remoto, memoria (~1 MB de stack por hilo de plataforma).
         */
        public static int ioBound(int cores, long waitMillis, long computeMillis) {
            return (int) (cores * (1 + (double) waitMillis / computeMillis));
        }
    }
}
