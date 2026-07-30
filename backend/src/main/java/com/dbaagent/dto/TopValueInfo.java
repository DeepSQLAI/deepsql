package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for representing top frequent values in a column.
 * Used in data skew analysis (BRAIN-design.md Section 3.2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopValueInfo {
    private String value;
    private Long count;
    private BigDecimal percentage;
}
