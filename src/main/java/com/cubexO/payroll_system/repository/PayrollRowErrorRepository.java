package com.cubexO.payroll_system.repository;

import com.cubexO.payroll_system.entity.PayrollRowError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PayrollRowErrorRepository extends JpaRepository<PayrollRowError, Long> {
}
