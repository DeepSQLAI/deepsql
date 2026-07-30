package com.dbaagent.util;

import com.dbaagent.model.SlowQuery;

import java.util.List;

/**
 * Shared capping policy for slow query responses.
 * Both the controller (API response) and KeyCustomerService (analysis input)
 * use this to guarantee they operate on the same ≤100 query set.
 */
public final class SlowQueryCapPolicy {

    public static final int MAX_QUERIES_IN_RESPONSE = 100;

    private SlowQueryCapPolicy() {}

    public static List<SlowQuery> capQueries(List<SlowQuery> queries) {
        if (queries == null) return List.of();
        return queries.size() > MAX_QUERIES_IN_RESPONSE
            ? queries.subList(0, MAX_QUERIES_IN_RESPONSE)
            : queries;
    }
}
