package com.cubexO.payroll_system.dto;

import com.cubexO.payroll_system.entity.PayrollJob;

import java.util.UUID;

public record PayrollUploadResponse(
        UUID jobId,
        String payrollMonth,
        PayrollJob.JobStatus status,
        boolean duplicate
) {}
