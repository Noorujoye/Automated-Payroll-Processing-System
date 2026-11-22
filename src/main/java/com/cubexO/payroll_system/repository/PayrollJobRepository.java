package com.cubexO.payroll_system.repository;

import com.cubexO.payroll_system.entity.PayrollJob;
import jakarta.persistence.Id;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollJobRepository extends JpaRepository<PayrollJob, UUID> {

    Optional<PayrollJob> findByFileHash(String fileHash);
}
