package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErEntity {
    private String name;
    private String type;
    private List<ErEntityColumn> columns = new ArrayList<>();
    private Position position;
    /** Approximate row count for sizing and display. */
    private Long rowCount;
    /** Table size in bytes, if available. */
    private Long sizeBytes;
    /** Brain/documentation description of what this table does. */
    private String description;
}
