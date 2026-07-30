package com.dbaagent.model;

import java.util.List;

public class TableIndex {
    private String name;
    private List<String> columns;
    private String type;
    private boolean unique;
    private boolean primary;

    public TableIndex() {
    }

    public TableIndex(String name, List<String> columns, String type, boolean unique, boolean primary) {
        this.name = name;
        this.columns = columns;
        this.type = type;
        this.unique = unique;
        this.primary = primary;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
        this.unique = unique;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }
}
