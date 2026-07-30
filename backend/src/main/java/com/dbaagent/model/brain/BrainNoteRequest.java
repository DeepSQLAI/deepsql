package com.dbaagent.model.brain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrainNoteRequest {
    private String connectionId;
    private String scopeType; // TABLE or COLUMN
    private String tableName;
    private String columnName;
    private String noteText;
    private String createdBy;
}
