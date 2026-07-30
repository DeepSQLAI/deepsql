package com.dbaagent.integration;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Test suite that runs all integration tests.
 *
 * Run this with: mvn test -Dtest=AllIntegrationTests
 *
 * Or run all integration tests with: mvn test -Dgroups=integration
 */
@Suite
@SuiteDisplayName("All Integration Tests")
@SelectPackages("com.dbaagent.integration")
public class AllIntegrationTests {
    // This class remains empty, it is used only as a holder for the above annotations
}
