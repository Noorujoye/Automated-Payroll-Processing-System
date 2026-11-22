package com.cubexO.payroll_system.service;

import com.cubexO.payroll_system.dto.PayrollUploadResponse;
import com.cubexO.payroll_system.entity.PayrollJob;
import com.cubexO.payroll_system.exception.WrongFileTypeException;
import com.cubexO.payroll_system.repository.PayrollJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayrollService {

    private final PayrollJobRepository jobRepository;
    private final PayrollWorkerService workerService;

    private static final String FILE_TYPE = "text/csv";
    private static final Set<String> REQUIRED_HEADER = Set.of(
            "employee_id", "name", "monthly_salary", "working_days", "present_days", "bonus");
    private static final Path UPLOAD_DIR = Paths.get("uploads/payroll");

    public PayrollUploadResponse initiatePayrollUpload(MultipartFile file, YearMonth payrollMonth) {

        //validating fileType and expected columns
        validateCsvBasics(file);

        Path saveFilePath = null;
        try {
            //Computing hash
            String fileHash = computeFileHash(file, payrollMonth);

            // Idempotency: return existing job before touching disk
            //Check DB — if duplicate, exit before writing anything to disk!
            Optional<PayrollJob> existingJob = jobRepository.findByFileHash(fileHash);
            if (existingJob.isPresent()) {
                PayrollJob job = existingJob.get();
                return new PayrollUploadResponse(
                        job.getJobId(),
                        job.getPayrollMonth(),
                        job.getJobStatus(),
                        true
                );
            }

            //File is new , save file for further processing
            Files.createDirectories(UPLOAD_DIR);
            UUID jobId = UUID.randomUUID();
            Path normalizedBaseDir = UPLOAD_DIR.toAbsolutePath().normalize();
            saveFilePath = normalizedBaseDir.resolve(jobId + "_" + payrollMonth + ".csv");

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, saveFilePath, StandardCopyOption.REPLACE_EXISTING);
            }

            PayrollJob job = PayrollJob.builder()
                    .jobId(jobId)
                    .payrollMonth(payrollMonth.toString())
                    .fileHash(fileHash)
                    .filePath(saveFilePath.toString())
                    .jobStatus(PayrollJob.JobStatus.QUEUED)
                    .build();

            //saving job, if there are changes in two table then I may use transaction
            jobRepository.save(job);

            //Trigger async background worker after job saved to db
            workerService.processPayrollFile(jobId, saveFilePath, payrollMonth);
            return new  PayrollUploadResponse(
                    job.getJobId(),
                    job.getPayrollMonth(),
                    job.getJobStatus(),
                    false
            );
        } catch (DataIntegrityViolationException raceException) {

            //two concurrent request uploaded same file simultaneously
            if (saveFilePath != null) {
                try {
                    Files.deleteIfExists(saveFilePath);
                } catch (IOException ignore) {
                    //ignore if file not exists
                }
            }

            PayrollJob winningJob = jobRepository.findByFileHash(computeHashQuietly(file, payrollMonth)).orElseThrow(() -> raceException);
            return new PayrollUploadResponse(
                    winningJob.getJobId(),
                    winningJob.getPayrollMonth(),
                    winningJob.getJobStatus(),
                    true
            );
        }catch (IOException e) {
            throw new RuntimeException("Failed to process payroll upload file!", e);
        }
    }

    private void validateCsvBasics(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty or missing!");
        }

        String fileName = file.getOriginalFilename();
        String contentType = file.getContentType();
        boolean hasCsvExtension = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
        boolean hasValidMime = contentType != null && FILE_TYPE.equals(contentType.toLowerCase(Locale.ROOT));

        if (!hasCsvExtension && !hasValidMime) {
            throw new WrongFileTypeException("File type must be CSV!");
        }
        //first line to validate header
        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine(); //header
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("CSV file has no header row.");
            }
            Set<String> ACTUAL_HEADER = Arrays.stream(
                    header.replace("\uFEFF","")
                            .toLowerCase(Locale.ROOT)
                            .split(","))
                    .map(String::trim)
                    .collect(Collectors.toSet());

            if (ACTUAL_HEADER.size() != REQUIRED_HEADER.size() || !ACTUAL_HEADER.containsAll(REQUIRED_HEADER)) {
                throw new IllegalArgumentException("Missing Required CSV Columns: " + REQUIRED_HEADER);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to validate CSV file!", e);
        }
    }

    /*
    private String saveFileAndComputeHash(MultipartFile file, Path saveFilePath, YearMonth payrollMonth) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream inputStream = file.getInputStream();
                 DigestInputStream digestStream = new DigestInputStream(inputStream, digest)) {
                 Files.copy(digestStream, saveFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
            digest.update(payrollMonth.toString().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("SHA-256 algorithm is not available!", e);
        }
    }

     */
    private String computeFileHash(MultipartFile file, YearMonth payrollMonth) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; // 8 KB buffer keeps RAM usage tiny
            int bytesRead;

            try (InputStream inputStream = file.getInputStream()) {
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            // same file for different or next month can be uploaded
            digest.update(payrollMonth.toString().getBytes(StandardCharsets.UTF_8));
            // converts the byte arr into 64 char hex string
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available!", e);
        }
    }

    private String computeHashQuietly(MultipartFile file, YearMonth payrollMonth) {
        try {
            return computeFileHash(file, payrollMonth);
        } catch (IOException e) {
            throw new RuntimeException("Failed to recompute file hash", e);
        }
    }
}
