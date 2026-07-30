package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Relationship {
    private String from;
    private String to;
    private String type;
    /** Column on the source (from) table. */
    private String foreignKey;
    /** Column on the source (from) table. */
    private String fromKey;
    /** Column on the target (to) table that is referenced. */
    private String toKey;
}
