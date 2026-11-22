package com.cubexO.payroll_system.repository;

import com.cubexO.payroll_system.entity.PayrollRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;

@Repository
public interface PayrollRecordRepository extends JpaRepository<PayrollRecord, Long> {
    boolean existsByEmployeeIdAndPayrollMonth(String employeeId, String  payrollMonth);
}
