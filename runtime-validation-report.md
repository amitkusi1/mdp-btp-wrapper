# Validation Report

Date: 2026-10-06

## Local results

| Check | Result | Evidence |
| --- | --- | --- |
| Build and package | PASS, exit code 0 | `mvn.cmd -B -q verify` |
| URI checks | PASS, 12 tests | `target/surefire-reports/TEST-com.example.mdp.ODataGatewayTest.xml` |
| HTTP integration | PASS, 7 tests | `target/surefire-reports/TEST-com.example.mdp.WrapperIntegrationTest.xml` |
| Issuer build and package | PASS, exit code 0 | `mvn.cmd -f test-issuer/pom.xml -B -q verify` |
| Issuer HTTP integration | PASS, 13 tests, no failures or skips | `test-issuer/target/surefire-reports/TEST-com.example.mdp.issuer.IssuerIntegrationTest.xml` |
| Startup | PASS | Spring Boot random-port Tomcat startup, public `/health` returns 200 |
| Live BTP/SAP | UNVERIFIED | CLI login and service provisioning complete; neither application deployed |
| Browser tests | Not applicable | API-only application |

Tests ran on Maven 3.9.11 with JDK 25, compiling for Java 21. Java 21 runtime
validation remains outstanding. Node 24.21.0 is available; Docker is unavailable.
Local JDK HTTP servers simulate JWKS and SAP boundaries, and the actual SAP SDK
HTTP client is exercised. No database, write operations or SAP data changes exist.
This verifies the wrapper locally, not BTP destination resolution or Cloud Connector.

An initial run failed two checks: SDK redirects ignored request-object settings,
and the ETag fixture omitted required quotation marks. Request configuration now
uses HttpClientContext and the fixture uses a valid quoted ETag. The same tests pass.

The standalone issuer uses Spring Authorization Server and a real local HTTP
listener in its tests. It issues signed tokens from authenticated token requests.
Tests verify signatures through public JWKS and apply the wrapper's issuer and
audience validation contract, including a token for the second permitted audience.
They cover missing/blank/duplicate/unknown audiences, wrong or absent credentials,
invalid scopes, unsupported grants, Basic and form client authentication, five-minute
expiry and absence of private key material in JWKS. This is contract validation,
not a deployed issuer-to-wrapper-to-SAP end-to-end test.

## Dependency findings

The advisory tool scanned 91 runtime coordinates. Spring Boot starter wrapper
artifacts and test dependencies were not included. A subsequent targeted scan
checked the changed library families and the outstanding Spring MVC finding.

| Library | Update | Reported advisories cleared |
| --- | --- | --- |
| Log4j API | 2.24.3 -> 2.25.5 | CVE-2026-49844 |
| Tomcat | 10.1.55 -> 10.1.60 | CVE-2026-68525, CVE-2026-65182, CVE-2026-65905 |
| Commons Lang | 3.17.0 -> 3.18.0 | CVE-2025-48924 |
| HttpCore 5 | 5.3.6 -> 5.4.3 | CVE-2026-54399, CVE-2026-54428 |
| HttpClient 5 | 5.5.2 -> 5.6.3 | CVE-2026-64607 |

Tomcat 10.1.58 was recommended by the scanner but unavailable from Maven Central;
10.1.60 was published and resolved successfully. Effective dependency versions
were confirmed with `mvn dependency:list` after the updates.

Remaining: **CVE-2026-47884**, Spring MVC 6.2.19 XsltView path handling.
The tool recommends Framework 7.0.9 or newer. This requires a coordinated major
upgrade and is deferred, not accepted or classified as unfixable. No XsltView
is configured in this application; exploitability has not been formally assessed.
Initial and final tool reports are in `target/cve-report-1.json` and
`target/final-cve-report.json`; a Maven clean removes these generated files.

### Issuer dependency check

A targeted scan covered the new authorization-server dependency and JSON/JWT
versions differing from the previously scanned wrapper. It found 13 advisories.
Jackson was aligned to BOM 2.22.3 and Nimbus to the patched 9.37.4 maintenance
branch (from 9.47); effective versions were checked using Maven dependency:list.
All 13 issuer tests passed again. Rescanning the changed coordinates and
authorization-server library reports only the existing Spring MVC finding above.
Reports: `test-issuer/target/cve-report.json` and
`test-issuer/target/final-cve-report.json`. This was a targeted dependency check,
not a new full audit of all inherited or test dependencies.

## Limitations and verdict

- The test issuer is implemented and packaged. It needs a dedicated client secret
  supplied outside source control and must be started or deployed before Postman use.
- The confirmed destination uses BasicAuthentication and an empty Location ID.
  The manifest reuses `dest-a3k`; both dedicated Connectivity and XSUAA services
  were provisioned in `KNX-02 Space`. Actual SAP access remains unverified.
- No runtime process or deployed app is left running; test servers shut down.
- Java editor analysis reports a named-package warning on WrapperApplication
  despite its explicit package declaration and successful Maven compile.
- VS Code has not imported the nested issuer Maven project and reports files as
  outside the wrapper classpath. Run `Java: Import Java Projects` to refresh it.
  Independent Maven compilation and tests pass; no editor configuration was overwritten.
- Maven/JDK emits deprecation and test-agent warnings; the test classpath also
  reports duplicate JSON implementations. These did not fail the build.

Overall: **local checks PASS; deployment and security sign-off PENDING**.