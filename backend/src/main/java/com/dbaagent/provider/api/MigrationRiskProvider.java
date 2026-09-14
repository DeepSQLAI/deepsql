package com.dbaagent.provider.api;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.service.migration.DdlFacts;

public interface MigrationRiskProvider {
    MigrationRiskReport classify(DdlFacts facts, TableFacts table);
}
