package com.dbaagent.service;

import com.dbaagent.model.SavedQuery;
import com.dbaagent.repository.SavedQueryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class SavedQueryService {

    @Autowired
    private SavedQueryRepository savedQueryRepository;

    /**
     * Save a new query
     */
    @Transactional
    public SavedQuery saveQuery(SavedQuery savedQuery) {
        log.info("Saving query: {} for connection: {}", savedQuery.getName(), savedQuery.getConnectionId());
        return savedQueryRepository.save(savedQuery);
    }

    /**
     * Update an existing query
     */
    @Transactional
    public SavedQuery updateQuery(UUID id, SavedQuery updates) {
        log.info("Updating query: {}", id);

        Optional<SavedQuery> existingOpt = savedQueryRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Query not found with id: " + id);
        }

        SavedQuery existing = existingOpt.get();

        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
        if (updates.getQuery() != null) {
            existing.setQuery(updates.getQuery());
        }
        if (updates.getTags() != null) {
            existing.setTags(updates.getTags());
        }
        if (updates.getIsFavorite() != null) {
            existing.setIsFavorite(updates.getIsFavorite());
        }
        if (updates.getFolder() != null) {
            existing.setFolder(updates.getFolder());
        }

        return savedQueryRepository.save(existing);
    }

    /**
     * Get a query by ID
     */
    public Optional<SavedQuery> getQueryById(UUID id) {
        return savedQueryRepository.findById(id);
    }

    /**
     * Get all queries for a connection
     */
    public List<SavedQuery> getQueriesByConnection(String connectionId) {
        log.info("Fetching all queries for connection: {}", connectionId);
        return savedQueryRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Get favorite queries for a connection
     */
    public List<SavedQuery> getFavoriteQueries(String connectionId) {
        log.info("Fetching favorite queries for connection: {}", connectionId);
        return savedQueryRepository.findByConnectionIdAndIsFavoriteTrueOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Get queries by folder
     */
    public List<SavedQuery> getQueriesByFolder(String connectionId, String folder) {
        log.info("Fetching queries in folder: {} for connection: {}", folder, connectionId);
        return savedQueryRepository.findByConnectionIdAndFolderOrderByCreatedAtDesc(connectionId, folder);
    }

    /**
     * Search queries
     */
    public List<SavedQuery> searchQueries(String connectionId, String searchTerm) {
        log.info("Searching queries with term: {} for connection: {}", searchTerm, connectionId);
        return savedQueryRepository.searchQueries(connectionId, searchTerm);
    }

    /**
     * Get queries by tag
     */
    public List<SavedQuery> getQueriesByTag(String connectionId, String tag) {
        log.info("Fetching queries with tag: {} for connection: {}", tag, connectionId);
        return savedQueryRepository.findByTag(connectionId, tag);
    }

    /**
     * Get distinct folders for a connection
     */
    public List<String> getFolders(String connectionId) {
        log.info("Fetching folders for connection: {}", connectionId);
        return savedQueryRepository.findDistinctFoldersByConnectionId(connectionId);
    }

    /**
     * Toggle favorite status
     */
    @Transactional
    public SavedQuery toggleFavorite(UUID id) {
        log.info("Toggling favorite for query: {}", id);

        Optional<SavedQuery> existingOpt = savedQueryRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Query not found with id: " + id);
        }

        SavedQuery existing = existingOpt.get();
        existing.setIsFavorite(!existing.getIsFavorite());

        return savedQueryRepository.save(existing);
    }

    /**
     * Delete a query
     */
    @Transactional
    public void deleteQuery(UUID id) {
        log.info("Deleting query: {}", id);
        savedQueryRepository.deleteById(id);
    }

    /**
     * Delete all queries for a connection
     */
    @Transactional
    public void deleteQueriesByConnection(String connectionId) {
        log.info("Deleting all queries for connection: {}", connectionId);
        List<SavedQuery> queries = savedQueryRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
        savedQueryRepository.deleteAll(queries);
    }
}
