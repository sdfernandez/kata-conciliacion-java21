package com.kata.conciliacion.enrichment;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Caché por clave que carga el valor desde un servicio remoto la primera vez (ej.: la config de
 * cada merchant). Un lock POR CLAVE para que dos hilos no carguen lo mismo a la vez, sin frenar a
 * los que piden claves distintas. Es código "legacy" muy común... y el escenario típico de pinning.
 */
public sealed interface KeyedCache<K, V> {

    V get(K key);

    /**
     * Versión con synchronized. En Java 21, si un virtual thread se BLOQUEA (IO, sleep) dentro de
     * un synchronized, queda PINNED: no puede desmontarse de su carrier thread, y ese carrier (hay
     * tantos como cores) queda bloqueado. Con N cores, solo N cargas simultáneas: se perdió la
     * escalabilidad de los virtual threads, sin ningún error visible.
     * Corregido en Java 24 (JEP 491): synchronized ya no pinnea.
     */
    final class Synchronized<K, V> implements KeyedCache<K, V> {
        private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
        private final Function<K, V> loader;

        public Synchronized(Function<K, V> loader) {
            this.loader = loader;
        }

        @Override
        public V get(K key) {
            // OJO: la IO NO va dentro de computeIfAbsent. ConcurrentHashMap usa synchronized
            // internamente sobre el bin: IO ahí adentro también pinnea (y bloquea otras claves).
            Entry<V> entry = entries.computeIfAbsent(key, k -> new Entry<>());
            synchronized (entry) {
                if (entry.value == null) {
                    entry.value = loader.apply(key); // IO dentro de synchronized → PINNING
                }
                return entry.value;
            }
        }

        private static final class Entry<V> {
            V value;
        }
    }

    /**
     * Misma lógica con ReentrantLock: los locks de java.util.concurrent son "virtual-thread
     * friendly". Si el hilo se bloquea con el lock tomado, se desmonta igual y libera el carrier.
     */
    final class Locking<K, V> implements KeyedCache<K, V> {
        private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
        private final Function<K, V> loader;

        public Locking(Function<K, V> loader) {
            this.loader = loader;
        }

        @Override
        public V get(K key) {
            Entry<V> entry = entries.computeIfAbsent(key, k -> new Entry<>());
            entry.lock.lock();
            try {
                if (entry.value == null) {
                    entry.value = loader.apply(key);
                }
                return entry.value;
            } finally {
                entry.lock.unlock(); // siempre en finally: a diferencia de synchronized, no se libera solo
            }
        }

        private static final class Entry<V> {
            final ReentrantLock lock = new ReentrantLock();
            V value;
        }
    }
}
