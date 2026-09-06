---
inclusion: always
---

# Standard: Running Tests During Spec Work

The full backend build (`./gradlew build` / `./gradlew test`) takes ~20 minutes because of the
Testcontainers integration suite. Do NOT run the full suite while implementing or fixing a spec.
Follow these rules every time you run tests.

## Rules

1. **Run only the affected test classes.** Select the specific test classes touched by the change
   with `--tests`, never the whole suite:

   ```
   ./gradlew test --tests "com.foremen.<pkg>.<TestClassA>" --tests "com.foremen.<pkg>.<TestClassB>" > /tmp/<spec-id>-test.log 2>&1; echo "EXIT=$?"
   ```

   - Include the production class's unit test, its property test(s), and any integration test that
     exercises the changed behavior — but only those.
   - When multiple classes are affected, pass multiple `--tests` filters in one invocation.

2. **Always write results to a temp log file and read from it.** The terminal wrapper truncates or
   drops output, so never rely on inline stdout. Redirect to a temp file and inspect that file:

   ```
   ./gradlew test --tests "..." > /tmp/<spec-id>-test.log 2>&1; echo "EXIT=$?"
   ```

   Then read `/tmp/<spec-id>-test.log` (or the JUnit XML under
   `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`) to determine pass/fail and to read
   failure stacktraces. The XML report is the source of truth for per-test status.

3. **Prefer the JUnit result XML for verification.** After a run, the authoritative pass/fail data is
   in `foremen-backend/build/test-results/test/TEST-<fully.qualified.ClassName>.xml`
   (`tests`, `failures`, `errors` attributes and `<failure>` messages). Read it rather than scrolling
   the log.

4. **Full suite only on explicit request.** Run `./gradlew build` or the whole `test` task only when
   the user explicitly asks for a full verification, and warn that it takes ~20 minutes.

5. **Compile-only checks are cheap.** To verify a change compiles without running tests, use
   `./gradlew compileJava` / `./gradlew compileTestJava` (also redirected to a temp log). Use the
   `getDiagnostics` tool for fast per-file error/type checks before running any Gradle task.

## Example

Fixing `PermissionResolver` and its tests:

```
./gradlew test \
  --tests "com.foremen.config.security.PermissionResolverBridgeMethodTest" \
  --tests "com.foremen.config.security.PermissionResolverCompletenessPropertyTest" \
  > /tmp/for-03-08-test.log 2>&1; echo "EXIT=$?"
```

Then read `/tmp/for-03-08-test.log` and/or
`foremen-backend/build/test-results/test/TEST-com.foremen.config.security.PermissionResolverBridgeMethodTest.xml`.
