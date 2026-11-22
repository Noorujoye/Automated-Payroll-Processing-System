package com.cubexO.payroll_system.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payroll_jobs")
@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollJob {

    @Id
    private UUID jobId;

    @Column(nullable = false)
    private String payrollMonth;

    @Column(nullable = false, unique = true)
    private String fileHash; //SHA-256 (fileBytes + payroll_month)

    @Column(nullable = false)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private JobStatus jobStatus = JobStatus.QUEUED;

    private int totalRows;
    private int processedRows;
    private int successCount;
    private int failedCount;

    @Column(length = 1000)
    private String errorSummary;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    public enum JobStatus {
        QUEUED,
        PROCESSING,
        COMPLETED,
        PARTIAL,
        FAILED
    };
}