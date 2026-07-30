package com.dbaagent.model.brain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainAlert {
    private String type;
    private String severity;
    private String message;
}
