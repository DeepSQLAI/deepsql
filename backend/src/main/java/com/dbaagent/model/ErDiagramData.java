package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErDiagramData {
    private List<ErEntity> entities = new ArrayList<>();
    private List<Relationship> relationships = new ArrayList<>();
}
