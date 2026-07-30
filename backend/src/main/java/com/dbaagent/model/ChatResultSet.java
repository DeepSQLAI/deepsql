package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResultSet {
    private String taskId;
    private String title;
    private String kind;
    private String status;
    private String summary;
    private List<String> dependsOn;
    private List<String> executedQueries;
    private QueryResult data;
    private Double confidence;
}
