package com.dbaagent.repository;

import com.dbaagent.model.QueryExample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QueryExampleRepository extends JpaRepository<QueryExample, String> {

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true " +
           "AND COALESCE(q.rejected, false) = false")
    List<QueryExample> findByConnectionIdAndSuccessfulTrue(@Param("connectionId") String connectionId);

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true " +
           "AND COALESCE(q.rejected, false) = false " +
           "ORDER BY q.createdAt DESC")
    List<QueryExample> findByConnectionIdAndSuccessfulTrueOrderByCreatedAtDesc(@Param("connectionId") String connectionId);

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true " +
           "AND COALESCE(q.rejected, false) = false " +
           "AND q.createdAt >= :since " +
           "ORDER BY q.createdAt DESC")
    List<QueryExample> findRecentSuccessful(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since
    );

    @Query("SELECT COUNT(q) FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true AND COALESCE(q.rejected, false) = false")
    Long countSuccessfulByConnection(@Param("connectionId") String connectionId);

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true " +
           "AND COALESCE(q.rejected, false) = false " +
           "AND LOWER(q.naturalLanguage) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY q.createdAt DESC")
    List<QueryExample> findByKeyword(
        @Param("connectionId") String connectionId,
        @Param("keyword") String keyword
    );

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true AND q.verified = true " +
           "AND COALESCE(q.rejected, false) = false")
    List<QueryExample> findByConnectionIdAndSuccessfulTrueAndVerifiedTrue(@Param("connectionId") String connectionId);

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true AND q.verified = false " +
           "AND COALESCE(q.rejected, false) = false " +
           "AND LOWER(q.sql) = LOWER(:sql)")
    List<QueryExample> findUnverifiedByConnectionAndSql(
        @Param("connectionId") String connectionId,
        @Param("sql") String sql
    );

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true " +
           "AND COALESCE(q.rejected, false) = false " +
           "AND LOWER(q.sql) = LOWER(:sql)")
    List<QueryExample> findActiveByConnectionAndSql(
        @Param("connectionId") String connectionId,
        @Param("sql") String sql
    );

    @Query("SELECT q FROM QueryExample q WHERE q.connectionId = :connectionId " +
           "AND q.successful = true AND COALESCE(q.rejected, false) = true")
    List<QueryExample> findByConnectionIdAndSuccessfulTrueAndRejectedTrue(@Param("connectionId") String connectionId);
}
