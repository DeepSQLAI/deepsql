package com.dbaagent.service.brain.analysis;

import com.dbaagent.model.ColumnDisambiguation;
import com.dbaagent.repository.ColumnDisambiguationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ColumnDisambiguationService {
    private final ColumnDisambiguationRepository repository;

    @Transactional(readOnly = true)
    public List<ColumnDisambiguation> getForConnection(String connectionId) {
        return repository.findByConnectionId(connectionId);
    }

    @Transactional
    public ColumnDisambiguation upsert(String connectionId, String columnName, String preferredTable) {
        String normalizedColumn = columnName != null ? columnName.trim() : "";
        String normalizedTable = preferredTable != null ? preferredTable.trim() : "";
        if (normalizedColumn.isEmpty() || normalizedTable.isEmpty()) {
            throw new IllegalArgumentException("Column name and preferred table are required");
        }

        return repository.findByConnectionIdAndColumnName(connectionId, normalizedColumn)
            .map(existing -> {
                existing.setPreferredTable(normalizedTable);
                return repository.save(existing);
            })
            .orElseGet(() -> repository.save(ColumnDisambiguation.builder()
                .connectionId(connectionId)
                .columnName(normalizedColumn)
                .preferredTable(normalizedTable)
                .build()));
    }
}
