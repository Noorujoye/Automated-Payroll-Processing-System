package com.cubexO.payroll_system.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "payroll_row_errors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollRowError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID jobId;

    private int rowNumber;

    private String employeeId;

    @Column(nullable = false, length = 500)
    private String errorMessage;

    @Column()
    private String rawRowData;
}
