# Verification harness

`Harness.java` contains the algorithmic cores of the service — short-code generation, the
SSRF address rules, referrer reduction, cache TTL clamping, click aggregation, client-IP
extraction and route-shape matching — copied verbatim out of the production classes.

It exists because this repository was authored in an environment without access to Maven
Central, so `mvn verify` could not be run. The harness has no dependencies and executes the
same cases the JUnit suite asserts:

```bash
javac tools/verify/Harness.java -d /tmp/harness && java -cp /tmp/harness Harness
```

Expected: `passed=45104 failed=0`.

It proves logic, not wiring. Once `mvn verify` runs against real Postgres and Redis, this
harness is redundant and should be deleted — it is scaffolding, and duplicated logic that
outlives its purpose becomes logic that drifts.
