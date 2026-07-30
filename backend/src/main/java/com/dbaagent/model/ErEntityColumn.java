package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErEntityColumn {
    private String name;
    private String type;
    private Boolean primaryKey;
    private Boolean nullable;
}
