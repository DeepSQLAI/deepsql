# Integration Test Suite

This directory contains comprehensive integration tests that test actual controllers with real database connections. **No mocking is used** - these tests verify that the entire application stack works correctly.

## Prerequisites

1. **PostgreSQL Database**: Ensure PostgreSQL is running with the `dba_agent` database
   ```bash
   # Default connection:
   # Host: localhost:5432
   # Database: dba_agent
   # Username: postgres
   # Password: postgres
   ```

2. **Test Connection**: You need at least one database connection configured in the system
   - The default test connection ID is: `6be6ae30-ba7e-4887-b72e-1a95da01f926`
   - Update this in `application-test.properties` if needed

3. **Redis/Valkey** (optional): Cache is disabled for tests, but if you have it running, it won't interfere

## Running the Tests

### Run All Integration Tests
```bash
cd backend
mvn test -Dtest=AllIntegrationTests
```

### Run Individual Test Classes
```bash
# Connection Controller tests
mvn test -Dtest=ConnectionControllerIntegrationTest

# Growth Monitoring tests
mvn test -Dtest=GrowthMonitoringControllerIntegrationTest

# Slow Query tests
mvn test -Dtest=SlowQueryControllerIntegrationTest

# Brain tests
mvn test -Dtest=BrainControllerIntegrationTest
```

### Run Specific Test Method
```bash
mvn test -Dtest=BrainControllerIntegrationTest#testAnalyzeKeyColumns
```

## Configuration

Configuration is in `src/test/resources/application-test.properties`:

```properties
# Override with environment variables
TEST_DB_URL=jdbc:postgresql://localhost:5432/dba_agent?sslmode=disable
TEST_DB_USERNAME=postgres
TEST_DB_PASSWORD=postgres
TEST_CONNECTION_ID=6be6ae30-ba7e-4887-b72e-1a95da01f926
```

## Test Coverage

### ConnectionController
- ✅ List all connections
- ✅ Get connection details
- ✅ Test connection
- ✅ Get schema information
- ✅ Get database statistics
- ✅ Handle non-existent connections

### GrowthMonitoringController
- ✅ Get growth history
- ✅ Get growth trends (all tables and specific table)
- ✅ Get growth anomalies
- ✅ Get growth configuration
- ✅ Trigger manual snapshot capture
- ✅ Get current table statistics
- ✅ Handle non-existent connections

### SlowQueryController
- ✅ Get slow query history
- ✅ Get latest slow query analysis
- ✅ Get specific history entry
- ✅ Get slow query summary statistics
- ✅ Analyze uploaded slow query log file
- ✅ Handle non-existent connections

### BrainController
- ✅ Get key column analysis
- ✅ Get key columns with limit
- ✅ Filter key columns by table name
- ✅ Trigger key column analysis
- ✅ Get schema classification
- ✅ Trigger schema classification analysis
- ✅ Get query anti-patterns
- ✅ Analyze query anti-patterns
- ✅ Get scalability simulation results
- ✅ Trigger scalability simulation
- ✅ Handle non-existent connections

## What These Tests Verify

These integration tests verify:

1. **Real Database Connectivity**: Tests connect to actual PostgreSQL and target databases
2. **Controller Endpoints**: All REST endpoints are accessible and return correct HTTP status codes
3. **Request/Response Flow**: JSON serialization/deserialization works correctly
4. **Business Logic**: Services execute correctly with real data
5. **Error Handling**: Non-existent resources return appropriate 404 errors
6. **Data Persistence**: Operations that modify data are persisted correctly

## Troubleshooting

### Tests fail with connection errors
- Ensure PostgreSQL is running: `pg_isready`
- Check database exists: `psql -l | grep dba_agent`
- Verify credentials in `application-test.properties`

### Tests fail with "connection not found"
- Update `TEST_CONNECTION_ID` in `application-test.properties` with a valid connection ID from your database
- Or create a test connection using the UI or API

### Tests timeout
- Some operations (like manual snapshot capture) may take time
- Increase timeout if needed, or ensure the target database is responsive

## Notes

- These tests use the **same database** as your development environment
- Tests **do not** clean up data between runs (they test real scenarios)
- For isolated testing, consider using a separate test database
- Some tests may fail if there's no historical data (e.g., slow query history)
