package com.cubexO.payroll_system.service;

import com.cubexO.payroll_system.entity.PayrollJob;
import com.cubexO.payroll_system.entity.PayrollRecord;
import com.cubexO.payroll_system.entity.PayrollRowError;
import com.cubexO.payroll_system.repository.PayrollJobRepository;
import com.cubexO.payroll_system.repository.PayrollRecordRepository;
import com.cubexO.payroll_system.repository.PayrollRowErrorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class PayrollWorkerService {

    private final PayrollJobRepository jobRepository;
    private final PayrollRecordRepository recordRepository;
    private final PayrollRowErrorRepository errorRepository;
    private final TransactionTemplate transactionTemplate;

    @Async
    public void processPayrollFile(UUID jobId, Path filePath, YearMonth payrollMonth) {
        PayrollJob job = jobRepository.findById(jobId).orElseThrow();
        job.setJobStatus(PayrollJob.JobStatus.PROCESSING);
        jobRepository.save(job);

        AtomicInteger rowNumber = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine(); // Skip header

            if (headerLine == null) {
                throw new IllegalStateException("CSV header columns missing during worker processing.");
            }

            Map<String, Integer> colIndex = parseHeaderIndices(headerLine);

            String employee_record;
            while ((employee_record = reader.readLine()) != null) {
                if (employee_record.isBlank()) continue;
                int currentRow = rowNumber.incrementAndGet();

                try {
                    PayrollRecord record = validateAndCalculate(employee_record, colIndex, jobId, payrollMonth);

                    // Save single row in its own transaction so failures never poison other rows
                    transactionTemplate.executeWithoutResult(status -> {
                        if (recordRepository.existsByEmployeeIdAndPayrollMonth(
                                record.getEmployeeId(), String.valueOf(YearMonth.parse(record.getPayrollMonth())))) {
                            throw new IllegalArgumentException(
                                    "Duplicate payroll record for employee " + record.getEmployeeId() + " in " + payrollMonth);
                        }
                        recordRepository.save(record);
                    });

                    success.incrementAndGet();
                } catch (DataIntegrityViolationException ex) {
                    failed.incrementAndGet();
                    String empId = extractEmployeeIdSafely(employee_record, colIndex);
                    saveRowError(jobId, currentRow, empId, employee_record, "Duplicate payroll record for employee " + empId + " in "+ payrollMonth);
                } catch (Exception rowEx) {
                    failed.incrementAndGet();
                    String empId = extractEmployeeIdSafely(employee_record, colIndex);
                    saveRowError(jobId, currentRow, empId, employee_record, rowEx.getMessage());
                }

                // Flush progress counters every 250 rows for live polling
                if (rowNumber.get() % 250 == 0) {
                    updateProgress(job, rowNumber.get(), success.get(), failed.get());
                }
            }

            finalizeJob(job, rowNumber.get(), success.get(), failed.get());

        } catch (Exception fatalEx) {
            log.error("Fatal error processing jobId={}", jobId, fatalEx);
            job.setJobStatus(PayrollJob.JobStatus.FAILED);
            job.setErrorSummary(truncate("Fatal file error: " + fatalEx.getMessage(), 1000));
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        }
    }

    private PayrollRecord validateAndCalculate(
            String employee_record,
            Map<String, Integer> colIndex,
            UUID jobId,
            YearMonth payrollMonth) {

        String[] data = employee_record.split(",", -1);

        String empId = data[colIndex.get("employee_id")].trim();
        if (empId.isEmpty()) {
            throw new IllegalArgumentException("employee_id is required");
        }
        String empName = data[colIndex.get("name")].trim();
        if (empId.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }

        BigDecimal monthlySalary;
        BigDecimal presentDays;
        BigDecimal workingDays;
        BigDecimal bonus;

        try {
            monthlySalary = new BigDecimal(data[colIndex.get("monthly_salary")].trim());
            presentDays = new BigDecimal(data[colIndex.get("present_days")].trim());
            workingDays = new BigDecimal(data[colIndex.get("working_days")].trim());
            bonus = new BigDecimal(data[colIndex.get("bonus")].trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric value in salary/attendance/bonus columns");
        }

        if (monthlySalary.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("monthly_salary cannot be negative");
        }
        if (bonus.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("bonus cannot be negative");
        }
        if (workingDays.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("working_days must be greater than zero");
        }
        if (presentDays.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("present_days cannot be negative");
        }
        if (presentDays.compareTo(workingDays) > 0) {
            throw new IllegalArgumentException("present_days cannot exceed working_days");
        }


        BigDecimal net_salary = monthlySalary
                    .multiply(presentDays)
                    .divide(workingDays, 10, RoundingMode.HALF_EVEN)
                    .add(bonus)
                    .setScale(2, RoundingMode.HALF_EVEN);
        return PayrollRecord.builder()
                .jobId(jobId)
                .employeeId(empId)
                .employeeName(empName)
                .payrollMonth(payrollMonth.toString())
                .basicSalary(monthlySalary.setScale(2, RoundingMode.HALF_EVEN))
                .netSalary(net_salary)
                .build();
        }

    private void saveRowError(
            UUID jobId,
            int rowNumber,
            String empId,
            String employeeRecord,
            String message) {

        try {
            PayrollRowError err = PayrollRowError.builder()
                    .jobId(jobId)
                    .rowNumber(rowNumber)
                    .employeeId(empId)
                    .rawRowData(employeeRecord)
                    .errorMessage(truncate(message, 500))
                    .build();
            errorRepository.save(err);
        } catch (Exception fatalEx) {
            log.error("Failed to persist row error for jobId={} at row={}",jobId, rowNumber, fatalEx);
        }
    }

    private void updateProgress(PayrollJob job, int processed, int success, int failed) {
        job.setProcessedRows(processed);
        job.setSuccessCount(success);
        job.setFailedCount(failed);
        jobRepository.save(job);
    }

    private void finalizeJob(PayrollJob job, int totalRows, int success, int failed) {
        job.setTotalRows(totalRows);
        job.setProcessedRows(totalRows);
        job.setSuccessCount(success);
        job.setFailedCount(failed);
        job.setCompletedAt(LocalDateTime.now());

        if (totalRows == 0) {
            job.setJobStatus(PayrollJob.JobStatus.FAILED);
            job.setErrorSummary("CSV file contains no employee records.");
        } else if (success == 0) {
            job.setJobStatus(PayrollJob.JobStatus.FAILED);
            job.setErrorSummary("All " + totalRows + " rows failed validation.");
        } else if (failed > 0) {
            job.setJobStatus(PayrollJob.JobStatus.PARTIAL);
            job.setErrorSummary(failed + " of " + totalRows + " rows failed validation.");
        } else {
            job.setJobStatus(PayrollJob.JobStatus.COMPLETED);
            job.setErrorSummary("All " + totalRows + " rows processed successfully.");
        }
        jobRepository.save(job);
    }

    private Map<String, Integer> parseHeaderIndices(String headerLine) {
        String[] headers = headerLine.replace("\uFEFF", "").split(",");
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim().toLowerCase(), i);
        }
        return map;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private String extractEmployeeIdSafely(String rawLine, Map<String, Integer> colIndex) {
        try {
            Integer idx = colIndex.get("employee_id");
            if (idx == null) return null;
            String[] data = rawLine.split(",", -1);
            if (idx < data.length) {
                String id = data[idx].trim();
                return id.isEmpty() ? null : id;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void validateValues(BigDecimal monthlySalary, BigDecimal bonus,
                            BigDecimal workingDays, BigDecimal presentDays) {
        if (isNegative(monthlySalary)) {
            throw new IllegalArgumentException("monthly_salary cannot be negative");
        }
        if (isNegative(bonus)) {
            throw new IllegalArgumentException("bonus cannot be negative");
        }
        if (!isPositive(workingDays)) {
            throw new IllegalArgumentException("working_days must be greater than zero");
        }
        if (isNegative(presentDays)) {
            throw new IllegalArgumentException("present_days cannot be negative");
        }
        if (presentDays.compareTo(workingDays) > 0) {
            throw new IllegalArgumentException("present_days cannot exceed working_days");
        }
    }

    private boolean isNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
