package com.cubexO.payroll_system.controller;

import com.cubexO.payroll_system.dto.ApiResponse;
import com.cubexO.payroll_system.dto.PayrollUploadResponse;
import com.cubexO.payroll_system.entity.PayrollJob;
import com.cubexO.payroll_system.service.PayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.*;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payrollService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PayrollUploadResponse>> uploadPayrollCSV(
            @RequestParam("file") MultipartFile file,
            @RequestParam("payroll_month") YearMonth payrollMonth) {

        PayrollUploadResponse response = payrollService.initiatePayrollUpload(file, payrollMonth);

        boolean alreadyFinished = response.duplicate() &&
                (response.status() == PayrollJob.JobStatus.COMPLETED
                        || response.status() == PayrollJob.JobStatus.PARTIAL
                        || response.status() == PayrollJob.JobStatus.FAILED);

        if (alreadyFinished) {
            return ResponseEntity.ok(
                    ApiResponse.ok("Duplicate file upload; returning existing payroll job.", response)
            );
        }

        String msg = response.duplicate()
                ? "Duplicate file upload; payroll job is already in progress."
                : "Payroll upload accepted and queued for processing.";
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(msg, response));
    }

    @GetMapping("/jobs/{job_id}")
    public void jobs(@PathVariable UUID job_id) {
    }

    @GetMapping("/jobs/{job_id}/results")
    public void job_result(@PathVariable UUID job_id) {

    }

    @GetMapping("/jobs/{job_id}/download")
    public void download(@PathVariable UUID job_id) {
    }

}
