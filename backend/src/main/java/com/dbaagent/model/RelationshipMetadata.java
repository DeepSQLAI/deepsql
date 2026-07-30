package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipMetadata {
    private String name;
    private String fromTable;
    private String fromColumn;
    private String toTable;
    private String toColumn;
    private String relationshipType; // "one-to-one", "one-to-many", "many-to-many"
    private String constraintName;
}



