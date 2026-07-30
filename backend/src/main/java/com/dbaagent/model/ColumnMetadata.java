package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetadata {
    private String name;
    private String dataType;
    private Long maxLength;
    private Boolean nullable;
    private Boolean primaryKey;
    private String defaultValue;
    private Integer ordinalPosition;
}



