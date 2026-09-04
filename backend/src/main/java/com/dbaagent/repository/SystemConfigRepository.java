package com.dbaagent.repository;

import com.dbaagent.model.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {

    /** Keys beginning with {@code prefix}. Used to enumerate namespaced config families. */
    @Query("SELECT c.key FROM SystemConfig c WHERE c.key LIKE CONCAT(:prefix, '%')")
    List<String> findKeysByPrefix(@Param("prefix") String prefix);
}
