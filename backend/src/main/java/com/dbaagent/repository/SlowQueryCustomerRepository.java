package com.dbaagent.repository;

import com.dbaagent.model.SlowQueryCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SlowQueryCustomerRepository extends JpaRepository<SlowQueryCustomer, String> {

    /** Find-or-create lookup key — one customer row per (connection, tenant id). */
    Optional<SlowQueryCustomer> findByConnectionIdAndCustomerId(String connectionId, String customerId);

    /** All customers seen on a connection, most recently active first. */
    List<SlowQueryCustomer> findByConnectionIdOrderByLastSeenAtDesc(String connectionId);

    /** Customers whose name has not been resolved yet — the lookup job's worklist. */
    List<SlowQueryCustomer> findByConnectionIdAndNameResolvedAtIsNull(String connectionId);

    /** Full reset — wipe every customer row for a connection. */
    void deleteByConnectionId(String connectionId);
}
