package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Table constraint information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintDetail {
    private String constraintName;
    private String constraintType;  // PRIMARY KEY, UNIQUE, CHECK, etc.
    private List<String> columns;
    private String checkClause;  // For CHECK constraints
}
