package com.kata.conciliacion.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, Long> {
}
