# Double-entry ledger

A money ledger whose rules live in PostgreSQL rather than in service code: postings are
append-only, an entry is refused unless its debits and credits balance, and account balances are
maintained by the database itself. The service layer can be wrong without the books going wrong.

Extracted from the payment service of an e-commerce system and made standalone — it builds and
runs with nothing else on the machine.

## Running it

```bash
docker run -d --name ledger-db -e POSTGRES_DB=ledgerdb -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:18-alpine
./mvnw spring-boot:run
```

Flyway creates the schema on first start. The API is on `:8091`, health on
`/actuator/health`, and the browsable description on
[`/swagger-ui.html`](http://localhost:8091/swagger-ui.html) — that page is the fastest way to see
what this service does.

## Authentication

Every endpoint except health and the API description needs a bearer token. Tokens come from the
project's own auth-service, not from Keycloak:

```
JWT_JWK_SET_URI=http://localhost:8090/api/v1/auth/.well-known/jwks.json
JWT_ISSUER=ecommerce-auth-service
```

Its issuer is a plain string rather than a URL and it publishes a JWKS and no discovery document,
so `spring.security.oauth2.resourceserver.jwt.issuer-uri` cannot be used — that property resolves
keys through OIDC discovery at `{issuer}/.well-known/openid-configuration`. The JWKS is named
directly instead and the issuer is compared as a string.

The acting user is read from the token's `sub`, never from the request body, so a caller cannot move
money out of an account they do not own by naming it.

## Running the tests

```bash
./mvnw verify
```

Needs Docker: the integration tests start their own PostgreSQL 18 through Testcontainers, the
same major version production runs. `LedgerThroughputIT` is tagged `load` and excluded from the
normal build.

## What the tests actually establish

| Property | Test |
|---|---|
| One idempotency key produces exactly one entry, even under 1000 concurrent requests | `LedgerConcurrencyIntegrationTest` |
| Opposite-direction transfers running together do not deadlock | `LedgerConcurrencyIntegrationTest` |
| A caller cannot move money out of an account they do not own (OWASP API1:2023, BOLA) | `LedgerInvariantsIntegrationTest` |
| Postings are immutable and every entry balances | `LedgerInvariantsIntegrationTest` |
| Request validation refuses malformed transfers before they reach the ledger | `LedgerControllerValidationIntegrationTest` |

## Deploying the demo

`compose.yaml` brings up the ledger, a database for it, and the auth-service that issues its
tokens — five containers in all.

```bash
LEDGER_DB_PASSWORD=... AUTH_DB_PASSWORD=... docker compose up -d
```

The passwords have no defaults on purpose: compose refuses to start without them rather than
standing up a money service on `postgres/postgres`.

**auth-service is the one prerequisite.** It lives in the e-commerce project this ledger was
extracted from and its image is not published yet, so `compose.yaml` refers to
`ghcr.io/<owner>/auth-service:latest` and will not find it until that happens. Building it needs the
whole parent repository, because it depends on shared modules — unlike this service, it cannot be
built from its own directory. Point `AUTH_SERVICE_IMAGE` at a locally built one to run the stack
before then.

Verified end to end against a real token rather than a test double — register through auth-service,
take the returned `accessToken`, and post a transfer:

```
HTTP 201  {"success":true,"data":{"entryId":1,"status":"POSTED"}}
WALLET-A = 999750.0000
WALLET-B =    250.0000
```

Sending the identical request again with the same `Idempotency-Key` also returns 201, and the
ledger still holds **one** journal entry: the repeat is recognised, not re-posted.

Two things about that stack are deliberate and cost something:

- **Ephemeral signing keys.** auth-service generates an RSA pair at startup, so no private key has to
  live in the compose file or the deployment's environment. Every restart invalidates every token it
  issued, and a second replica would reject the first one's. Correct for one demo instance and wrong
  for anything else; set `AUTH_JWT_PRIVATE_KEY` / `AUTH_JWT_PUBLIC_KEY` for a real deployment.
- **Redis is optional.** Only the token deny list uses it, and auth-service catches the connection
  failure and carries on. Losing Redis costs logout — a revoked token stays usable until it expires
  on its own — and nothing else.

## Two details worth knowing

**Lock order is fixed in SQL, not in Java.** `LedgerAccountBalanceRepository` locks rows with
`SELECT ... WHERE account_id IN :ids ORDER BY account_id` under `PESSIMISTIC_WRITE`. The
`ORDER BY` is what actually forces a consistent lock order; sorting the ids in Java first is not
enough, because the planner is free to return rows in another order. Dropping it looks safe until
the plan changes.

**Flyway needs `spring-boot-starter-flyway` on Spring Boot 4**, not `flyway-core`. The
auto-configuration moved into `spring-boot-flyway`, which only the starter pulls in. With
`flyway-core` alone the build succeeds, the migrations never run, and Hibernate then fails
validation on a missing table.
