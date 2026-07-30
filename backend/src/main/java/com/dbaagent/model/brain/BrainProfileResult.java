package com.dbaagent.model.brain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainProfileResult {
    private String connectionId;
    private int tablesProfiled;
    private int columnsProfiled;
    private int tablesSkipped;
    private int columnsSkipped;
    private LocalDateTime profiledAt;
}
