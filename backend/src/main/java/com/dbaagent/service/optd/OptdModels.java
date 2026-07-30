package com.dbaagent.service.optd;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class OptdModels {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptdOptimizeRequest {
        @JsonProperty("db_type")
        private String dbType;
        private String query;
        private OptdSchema schema;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptdOptimizeResponse {
        @JsonProperty("plan_text")
        private String planText;
        @JsonProperty("plan_signature")
        private String planSignature;
        @JsonProperty("estimated_cost")
        private Double estimatedCost;
        @JsonProperty("estimated_rows")
        private Double estimatedRows;
        @Builder.Default
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptdSchema {
        @Builder.Default
        private List<OptdTable> tables = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptdTable {
        private String name;
        @JsonProperty("row_count")
        private Long rowCount;
        @Builder.Default
        private List<OptdColumn> columns = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptdColumn {
        private String name;
        @JsonProperty("data_type")
        private String dataType;
        private Boolean nullable;
    }
}
