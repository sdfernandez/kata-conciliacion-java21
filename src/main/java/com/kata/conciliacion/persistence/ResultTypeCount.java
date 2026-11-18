package com.kata.conciliacion.persistence;

/** Fila de un GROUP BY result_type. */
public record ResultTypeCount(ResultType type, Long count) {
}
