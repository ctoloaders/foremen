package com.foremen.qa;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * JUnit 5 (Platform) Cucumber suite for the Foremen smoke tests.
 *
 * <p>Selects every scenario tagged {@code @smoke} by default. The tag filter is overridable at
 * runtime via the {@code cucumber.filter.tags} system property (the {@code smokeTest} Gradle task
 * maps {@code -DQA_TAGS=...} onto it), so a single spec's slice can be run, e.g.
 * {@code -DQA_TAGS="@smoke and @FOR-03"} (Requirements 1.4, 4.2, 4.3).
 *
 * <p>Newly added {@code @smoke @FOR-0N} feature files under {@code src/test/resources/features}
 * are discovered automatically with no change to this runner.
 *
 * <p>Reporting plugins (Requirement 10): the standard Cucumber HTML + JSON are emitted for CI, and
 * {@link com.foremen.qa.report.DemoReportPlugin} builds the client-facing demo report (per-step
 * screenshots, run summary, MD run-report) under {@code QA_REPORT_DIR} in a run-scoped folder.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.foremen.qa.steps,com.foremen.qa.support")
// NB: the tag filter is intentionally NOT declared as a @ConfigurationParameter here. A directly
// supplied @ConfigurationParameter takes precedence over system properties on the JUnit Platform,
// which would make the `smokeTest` task's -DQA_TAGS override (mapped onto cucumber.filter.tags) a
// no-op. Instead the default `@smoke` lives in junit-platform.properties (the default config file,
// which a system property overrides), so `-DQA_TAGS="@smoke and @FOR-03"` slices a spec correctly
// (Requirements 4.2, 4.3).
@ConfigurationParameter(
        key = PLUGIN_PROPERTY_NAME,
        value = "pretty,"
                + "html:build/qa-report/cucumber.html,"
                + "json:build/qa-report/cucumber.json,"
                + "com.foremen.qa.report.DemoReportPlugin")
public class RunSmokeTest {
    // Intentionally empty: this class is a declarative Cucumber suite descriptor.
}
