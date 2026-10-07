# Internal OAuth Audience Test Issuer

Test-only Spring Authorization Server for client-credentials requests with an
explicit `audience` parameter. This is a separate application, not part of the
wrapper JAR, and requires no SAP service bindings.

## Contract

| Setting | Value |
| --- | --- |
| Token endpoint | `POST /oauth/token` |
| Public signing keys | `GET /.well-known/jwks.json` |
| OAuth metadata | `GET /.well-known/oauth-authorization-server` |
| Liveness | `GET /health` |
| Grant type | `client_credentials` |
| Client ID | `mdp-test-client` by default |
| Client authentication | HTTP Basic, or `client_id` and `client_secret` in the form body |
| Allowed audiences | `urn:mdp:btp-wrapper`, `urn:mdp:other-api` |
| Allowed scope | `odata.read` |
| Signing algorithm | RS256 |
| Token lifetime | Five minutes; no refresh token |

Send `Content-Type: application/x-www-form-urlencoded`. Exactly one nonempty
audience is required. Audience matching is exact. The framework validates client
credentials and scopes; the customizer sets the JWT `aud` to the selected value.
If scope is omitted, the registered `odata.read` scope is granted.

## Local setup

Use JDK 21+ and Maven 3.9+. Run these commands from the repository root.
The root Maven command builds only the wrapper; build the issuer separately:

```powershell
mvn.cmd -f test-issuer/pom.xml clean verify
```

In a terminal, enter a randomly generated secret from your password manager.
Use at least 32 ASCII characters; keep it at most 72 bytes for BCrypt. Store the
same value in a local Postman Vault secret. Do not paste it into chat or commit it.

```powershell
$secret = Read-Host 'Test client secret' -AsSecureString
$env:TEST_CLIENT_SECRET = [System.Net.NetworkCredential]::new('', $secret).Password
$env:TEST_ISSUER_URI = 'http://localhost:9000'
$env:PORT = '9000'
mvn.cmd -f test-issuer/pom.xml spring-boot:run
```

In a second terminal, start the wrapper:

```powershell
$env:JWT_ISSUER_URI = 'http://localhost:9000'
$env:JWT_JWK_SET_URI = 'http://localhost:9000/.well-known/jwks.json'
$env:PORT = '8080'
mvn.cmd spring-boot:run
```

If either port is occupied, choose a free one and adjust the issuer URI and JWKS
URL consistently. Do not change the issuer hostname between `localhost` and
`127.0.0.1`: the issuer claim must match exactly. Stop with Ctrl+C; remove the
secret environment variable afterward with `Remove-Item Env:TEST_CLIENT_SECRET`.

## Postman

Create a POST request to `http://localhost:9000/oauth/token` with Basic Auth:
username `mdp-test-client`, password from your local Vault. Under Body choose
`x-www-form-urlencoded`:

```text
grant_type = client_credentials
audience   = urn:mdp:btp-wrapper
scope      = odata.read
```

Use the returned `access_token` as the Bearer token for
`GET http://localhost:8080/test`. This should return 200 with
`{"status":"authorized"}` without calling SAP. For your colleague's feature,
configure the same token URL, client credentials, scope and audience in the MDP
OAuth client instead of manually making the token request.

| Scenario | Expected result |
| --- | --- |
| Correct credentials, wrapper audience | Token 200; wrapper `/test` 200 |
| Audience `urn:mdp:other-api` | Token 200; wrapper `/test` 401 |
| Audience `urn:unknown` | Token 400, `invalid_target` |
| Missing, blank or duplicate audience | Token 400, `invalid_request` |
| Incorrect client secret | Token 401, `invalid_client` |
| Scope `admin` | Token 400, `invalid_scope` |
| Expired token | Wrapper 401 after expiry plus its default clock-skew allowance |

Local authentication testing needs no SAP credentials. Real `/odata/$metadata`
access still requires the BTP deployment and destination connectivity.

## BTP deployment

The manifest intentionally contains no client secret. Before deploying, review
the remaining Spring MVC advisory in the root validation report. This is not a
production authorization service and has not been deployed by this implementation.

Choose an unused route hostname and an application domain returned by `cf domains`:

```powershell
mvn.cmd -f test-issuer/pom.xml clean verify
cf push -f test-issuer/manifest.yml --no-start --var 'issuer-host=<unique-test-issuer-host>' --var 'apps-domain=<application-domain>'
```

In BTP Cockpit, open the `mdp-test-issuer` application's user-provided environment
variables and set `TEST_CLIENT_SECRET` to the random test secret. Enter it there
directly, not through chat. Anyone with permission to inspect application
environment variables may see it, so use a dedicated disposable test credential.

```powershell
cf start mdp-test-issuer
cf app mdp-test-issuer
```

Deploy the wrapper with that exact public issuer origin:

```powershell
mvn.cmd clean verify
cf push -f manifest.yml --var 'jwt-issuer-uri=https://<issuer-host>.<application-domain>' --var 'jwt-jwk-set-uri=https://<issuer-host>.<application-domain>/.well-known/jwks.json'
```

Use the HTTPS routes in Postman, first `/test`, then `/odata/$metadata`.
The wrapper manifest reuses `dest-a3k`, `mdp-wrapper-connectivity` and
`mdp-wrapper-xsuaa`. The issuer itself must not receive these SAP bindings.

## Test-only limitations

- An RSA key is generated in memory at each start. A restart invalidates old
  tokens once consumers refresh JWKS. Use exactly one issuer instance and avoid
  rolling or blue-green deployments with overlapping keys. Stable keys, rotation,
  shared storage and high availability are deliberately not implemented.
- One client is registered in memory with a BCrypt-encoded secret. There is no
  registration UI, persistent authorization database or per-client administration.
- No public-Internet abuse protections or operational production hardening are
  provided. Restrict access to the testing team, use disposable credentials and
  stop or delete the app when testing ends. HTTPS is required outside loopback.
- `audience` handling is our internal test contract, not a claim of compatibility
  with every external OAuth provider. XSUAA is not being changed to support it.
- Never enable request-body or Authorization-header logging; requests contain
  credentials and responses contain access tokens.

The HTTP integration tests verify token issuance, signature verification via
public JWKS, audience selection and rejection, scope and credential errors, and
absence of private key fields in JWKS. They use fresh random test secrets and
shut down their HTTP server automatically.