package com.dbaagent.service.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryPropertiesTest {

    @Test
    void enabledByDefault() {
        TelemetryProperties props = new TelemetryProperties();
        assertTrue(props.isEffectivelyEnabled(null, null));
    }

    @Test
    void disabledByDoNotTrackEnvVar() {
        TelemetryProperties props = new TelemetryProperties();
        assertFalse(props.isEffectivelyEnabled("1", null));
        assertFalse(props.isEffectivelyEnabled("true", null));
    }

    @Test
    void disabledByDeepsqlOptOut() {
        TelemetryProperties props = new TelemetryProperties();
        assertFalse(props.isEffectivelyEnabled(null, "1"));
        assertFalse(props.isEffectivelyEnabled(null, "true"));
    }

    @Test
    void disabledByAdminUiToggle() {
        TelemetryProperties props = new TelemetryProperties();
        props.setEnabled(false);
        assertFalse(props.isEffectivelyEnabled(null, null));
    }
}
