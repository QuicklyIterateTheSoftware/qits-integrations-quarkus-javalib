# qits-integrations-quarkus-javalib

Quarkus glue every qits service needs and no service owns. The reactor is
`eu.wohlben.qits:qits-integrations-quarkus`, with five modules today:

| Module | Coordinates | What it is |
| --- | --- | --- |
| `qits-auth-core/` | `eu.wohlben.qits:qits-auth-core` | Forward-auth for user traffic, claim checks for machine tokens, and the one gate that turns machine enforcement on. |
| `qits-arch-rules/` | `eu.wohlben.qits:qits-arch-rules` | Shared rules: platform conventions a service's own build enforces. The causation-row completeness rules, the test-profile budget, and the datasource baseline. |
| `qits-db-core/` | `eu.wohlben.qits:qits-db-core` | The database resilience baseline: `PatientPgDriver`, which holds a connection request through a cutover, and `DbRetry` for work that must survive one. |
| `qits-environment-core/` | `eu.wohlben.qits:qits-environment-core` | The environment tier as an ambient value: `X-Qits-Environment` stamped on every outgoing REST-client request from `qits.environment` (`platform` where a deployment injects none), and `CallerEnvironment` holding the caller's tier for the receiving resource method. |
| `qits-service-mock/` | `eu.wohlben.qits:qits-service-mock` | Recording mocks of platform services for cross-service integration tests: the generic `MockService` (stub JSON routes, record every request), plus `idp.MockIdp` adding the one thing canned JSON can't fake — key material and RS256 token minting. Test scope for consumers. |

Build: `./mvnw verify`. A clone of this repo alone must build — no monorepo, no
prior `mvn install`.

---

# qits-db-core

## What it is

Two halves of one answer to the same fact: **the platform restarts its own postgres**, and a cutover
kills every connection every service is holding. Measured twice on 2026-08-11: a deployment ended
`FAILED: [JDBCConnectionException …]` while its own container was healthy, and a catalogue read
answered 404 for a repository that exists. In both cases the database was back seconds later.

| | |
| --- | --- |
| `PatientPgDriver` | Holds a *connection request* while postgres comes back. Universal — three config lines per datasource, no code. |
| `DbRetry` | Retries a *block of work* whose connection died mid-flight. Placed by hand, at read seams. |
| `DbRetry.inNewTx` | The same, for a **write**: it owns the transaction, so it can retry only the attempts that certainly did not commit. |

```xml
<dependency>
    <groupId>eu.wohlben.qits</groupId>
    <artifactId>qits-db-core</artifactId>
    <version>…</version>
</dependency>
```

The pgjdbc dependency is **provided**, never compile: every consumer already ships
`quarkus-jdbc-postgresql`, and a second copy on the classpath would make this module's pgjdbc
version a fleet-wide pin.

---

## PatientPgDriver

A `java.sql.Driver` that delegates to `org.postgresql.Driver` and retries "the database is not there
yet" until a deadline. Adoption is one line per datasource, beside the two pool lines it composes
with:

```properties
quarkus.datasource.<name>.jdbc.driver=eu.wohlben.qits.db.PatientPgDriver
quarkus.datasource.<name>.jdbc.validate-on-borrow=true
quarkus.datasource.<name>.jdbc.acquisition-timeout=15S
```

`validate-on-borrow` turns a dead pooled connection into a fresh creation *attempt*, which is what
this driver makes patient. `acquisition-timeout` keeps the Agroal waiter alive while it works. All
three or none: each one does less than it reads as without the other two.

**Held, not buffered.** The caller's thread blocks inside `connect`, before anything has executed.
Nothing is acknowledged early, nothing is queued, nothing is applied later. That is why patience here
is safe for **writes** as well as reads, where retrying an *operation* is not — a commit whose
acknowledgement was lost still happened. A request that outlives the deadline gets the real failure
and nothing has happened anywhere. Measured 2026-08-11: 6 workers, 240 calls, an 8.2s hard outage
mid-run, zero failures; the straddling calls held ~8.6s and succeeded ~0.3s after postgres accepted
again.

**What is retried, and nothing else:**

| SQLState | |
| --- | --- |
| `08*` | The standard connection-exception class — refused, unreachable, connect timeout. |
| `57P03` | "The database system is starting up." Crash recovery accepts TCP ~1.3s before it serves, so refused-only patience gives up exactly one phase early. |

Everything else is rethrown on the first attempt. A wrong password fails in ~114ms with `28P01`,
measured — the narrowness is the feature.

| JDBC property | Default | |
| --- | --- | --- |
| `qitsPatienceDeadlineMs` | `14000` | Under the fleet's 15S acquisition-timeout on purpose, so a caller sees the database's own refusal rather than a generic acquisition timeout. |
| `qitsPatiencePauseMs` | `250` | The wait between attempts. |

Set them per datasource with
`quarkus.datasource.<name>.jdbc.additional-jdbc-properties.qitsPatienceDeadlineMs=…`. Both are
stripped from what pgjdbc receives, and an unreadable value falls back to the default rather than
failing a connection while the database is healthy.

**It bounds nothing itself, on purpose.** Agroal serializes all connection creation on one executor
thread per pool (measured: peak in flight 1, with ten concurrent callers), so at most one patient
loop runs per datasource and every other caller waits in the acquisition queue under its own 15s
timeout. A semaphore here would bound something that is already single flight.

It accepts plain `jdbc:postgresql:` URLs — Agroal is handed the driver class explicitly, so there is
no `DriverManager` ambiguity and the injected `QITS_RESOURCE_*_URL` contract is untouched. For the
same reason it registers itself with `DriverManager` nowhere: instantiating it is Agroal's job, and a
global registration could shadow pgjdbc for a caller that never asked for patience.

**Watch item, native builds.** The first consumer to build native must prove that a driver class
named only in configuration still resolves in the image; reflection registration may be needed. Until
that build is green, treat native adoption as unproven.

The rule that keeps a service from shipping two of the three lines is
`DatasourceBaselineRules`, in `qits-arch-rules` below.

---

## DbRetry

One static helper, no CDI bean, no ORM dependency. It runs a block of database work and **retries
connection-class failures only**, until a short deadline. It is what `PatientPgDriver` cannot be:
patience for a connection that died *after* statements ran.

```java
// 15 seconds by default; pass a Duration for a call site that needs another.
var repo = DbRetry.call("read the repository catalogue", () -> repositories.findByName(name));

DbRetry.run("mark the deployment active", () -> { … });
```

### Where it goes, and where it must not

Wrap work that must survive a short outage — bookkeeping that runs **after** something irreversible
has already happened, and reads a caller is waiting on. The block must be re-runnable: a read is, a
write that re-reads what it touches and sets it to the same values is. **A bare `insert` is not** —
a commit whose outcome the connection died before reporting would be duplicated by a second
attempt.

It sleeps the calling thread, so on a request thread it holds the request open for up to the
deadline. Wrap the operations that need it, not every query.

### It needs the pool configured for it

`validate-on-borrow` (Quarkus default `false`) evicts a dead connection instead of handing it to a
caller. Without it this retry spends its whole deadline receiving the same dead connection.
`acquisition-timeout` (Quarkus default `5S`) bounds how long a request waits on a starved pool. Both
keys verified against `io.quarkus.agroal.runtime.DataSourceJdbcRuntimeConfig` (Quarkus 3.34.6).

The superproject's `docs/project-setup-quinoa-angular.md` carries the fleet-wide rule and the two
companion rules that go with it: a failed read is a 5xx and never a "not found", and cross-service
writes during bootstrap keep client-side retry. `db-patience-plan.md` beside it carries the
measurements.

## DbRetry.inNewTx — the same patience, for a write

`DbRetry.call` cannot help a write, and says so: a connection that died during the commit round trip
would be retried into a second write. `inNewTx` closes that gap by **owning the transaction
boundary**, which is the only position from which the outcome is knowable.

```java
var id = DbRetry.inNewTx("record the deployment", () -> deployments.record(spec));

DbRetry.runInNewTx("mark the run finished", () -> run.finish(at));
```

Every attempt is `QuarkusTransaction.requiringNew()`, so no attempt inherits the previous one's dead
connection or its rolled-back state. The `Runnable` form has its own name rather than being an
overload: `() -> repository.delete(id)` fits a `Runnable` and a `Callable` at once, and two
same-named methods would make that call site ambiguous.

### The taxonomy

| | |
| --- | --- |
| **Retry** | A connection-class failure thrown **out of the body** — the statement phase. Quarkus rolls a failed body back and never commits it, so the position is known: nothing was written. |
| **Rethrow** | Everything the transaction manager itself reports — commit, rollback, heuristic, XA. That is where the ambiguity lives. |
| **Rethrow** | Every non-connection failure, exactly as `DbRetry.call` does. A constraint violation is equally certain not to have committed, and equally certain to fail the same way on the second attempt. |

Both conditions are needed, and they are not the same condition. *The body threw it* is what makes a
second attempt **safe**. *It is a connection failure* is what makes a second attempt **worth
making**. Uncertain classifies as rethrow: a caller erroring honestly beats a double-executed write.

### The residue, which is by design

**A failure inside the commit acknowledgement is rethrown.** That one round trip is genuinely
undecidable from the client — the database may have committed and lost the answer on the way back.
Nothing here can make it safe, and this method does not pretend to.

**A rollback the transaction manager claims is not evidence**, which is the surprising half.
Measured on a real wire, 2026-08-11 (`DbRetryInNewTxTest`): killing the connection inside the commit
produces `QuarkusTransactionException: jakarta.transaction.RollbackException: ARJUNA016053: Could
not commit transaction.` — no cause, no mention of a connection. Narayana spells "the commit could
not be delivered" and "the transaction was rolled back before committing" with the same exception
type, so believing the word *rollback* there would retry exactly the write that may already be in
the database. The whole commit phase is therefore rethrown.

**A flush-phase loss is a commit-phase loss unless you make it otherwise.** An ORM flushes at commit
by default, which puts the write on the far side of that line. `entityManager.flush()` (or
`Panache.flush()`) as the last statement of the body moves it into the statement phase, where the
classification is certain. One line, and it is the difference between this helping and this
reporting.

**The retry is the whole body.** Anything in it that is not a database write — a message sent, a
file written, a counter bumped — happens once per attempt.

### Idempotent writes need none of this

A write that is idempotent by construction — an upsert, an insert on a natural key, a
set-to-a-fixed-value update — is safe under plain `DbRetry.call` no matter where the connection
died, because a second execution of it is not a second effect. **That judgement belongs at the call
site**, the only place that knows the write's shape; `inNewTx` cannot see it and must not assume it.

### What it needs

A running Quarkus application — a transaction manager — which the rest of this class does not. It
needs no *request* context, so a background worker may call it. Call it from outside any open
transaction: joining an existing one would make "a fresh transaction per attempt" a lie. A checked
exception from the body reaches the caller unchanged.

### How the claims are proved

Against a **real postgres**, through a hand-rolled TCP proxy that dies on command
(`testdb/KillableProxy`, the `StubEventsServer` bargain in fewer lines). Zonky spawns postgres from
Maven artifacts, so a clone of this repo alone still tests green with no docker. Five wire tests: a
statement-phase kill retried with the row landing exactly once, a kill before the first statement
retried, a business failure not retried and rolled back, a `COMMIT` swallowed mid-flight and
rethrown, and an outage that outlives the deadline. `DbRetryInNewTxClassificationTest` pins the
outcomes a single-resource local transaction never produces — heuristics, XA.

**The wire suite skips under root**, because zonky's `initdb` refuses to run as the superuser and the
platform's CI step containers are Alpine running as one. That is a real hole rather than a hidden
one: the wire proof runs on a developer host, the classifier's unit tests run everywhere, and this
repo's release pipeline builds with `-DskipTests` in any case.

## What it depends on, and why so little

`jboss-logging`, plus two provided-scope dependencies: pgjdbc for the driver to delegate to, and
`quarkus-narayana-jta` for `inNewTx` to name `QuarkusTransaction`. Provided is what keeps both from
being felt — it is not transitive, so no consumer inherits a JTA it did not ask for and this
module's Quarkus version never becomes a fleet-wide pin. Every consumer of `inNewTx` already has JTA;
a consumer that only wants `DbRetry.call` or `PatientPgDriver` gets nothing new.

The alternative considered and rejected for the JTA dependency: a functional seam, an `inNewTx` that
takes a transaction runner the consumer supplies. It costs nothing in weight and everything in
correctness — the classification is only sound because *this* code owns the transaction boundary and
therefore knows a body failure was rolled back. A seam hands that knowledge back to the caller this
method exists to protect from getting it wrong.

Hibernate's `JDBCConnectionException` is matched **by
fully-qualified name**, the qits-arch-rules trick, so the consumer's ORM and this module's version
stay uncoupled and a bare clone builds with no platform registry. The JDK's own `java.sql`
connection exceptions are matched by type, and SQLState `08*` / `57P0x` plus the pool's own
acquisition-timeout wording cover the rest.

The price is qits-arch-rules': a rename in Hibernate silently un-matches. The mirror in this
module's test sources is where that surfaces first.

---

# qits-arch-rules

One test-scope dependency and a short test class turn the platform's conventions into build
failures — over the service's bytecode, and over its configuration:

```xml
<dependency>
    <groupId>eu.wohlben.qits</groupId>
    <artifactId>qits-arch-rules</artifactId>
    <version>…</version>
    <scope>test</scope>
</dependency>
```

```java
@AnalyzeClasses(packages = "eu.wohlben.qits.<service>",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {
  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
}
```

## CausationRowRules

Guards qits-eventstream's row stamping, whose participation is opt-in per
entity and therefore silently forgettable. Three rules: every `@Entity` either implements
`CausedRow` or declares `@Uncaused`; every entity that implements `CausedRow` lists
`CausationStamp` in its `@EntityListeners`; nothing carries `@Uncaused` and `CausedRow` at once.
Forgetting becomes a red build naming the entity; opting out becomes one reviewable line.

The rules judge types **by fully-qualified name** — this module deliberately depends on neither
qits-eventstream nor jakarta.persistence. Bytecode carries names, a bare clone builds without the
platform registry, and the two libraries' versions stay uncoupled. The contract that buys: a rename
in qits-eventstream must update the rules' constants and the fixture mirror in this module's test
sources, where the drift surfaces first.

## TestProfileBudgetRules

**At most one `QuarkusTestProfile` per module**, unless the duplicate implements
`NecessaryTestProfileDuplication`. Profiles live in test sources, so this rule gets its own test
class — one `@AnalyzeClasses` cannot both include and exclude tests:

```java
@AnalyzeClasses(packages = "eu.wohlben.qits.<service>",
    importOptions = ImportOption.OnlyIncludeTests.class)
class TestProfileBudgetTest {
  @ArchTest static final ArchTests PROFILES = ArchTests.in(TestProfileBudgetRules.class);
}
```

qits-workspaces-service's CI gate died four times on 2026-09-14 with `Process Exit Code: 137` — the
OOM killer — inside qits-ci's hard `QITS_CI_MEMORY_LIMIT=4g` step container: no failing test, no
stack trace, and a build that read as an infrastructure flake for as long as anybody kept rerunning
it. Measured cause: **each distinct `@TestProfile` is a Quarkus restart, and each restart retains its
application's classloader** — about 125 MB of metaspace never given back for the life of the fork.
Fourteen applications in one module's suite came to 1.71 GiB committed metaspace inside a 3.41 GiB
fork. `-XX:MaxMetaspaceSize` reclaims none of it; it only turns the SIGKILL into
`OutOfMemoryError: Metaspace`. The only lever is *fewer applications*, and profiles mint them.

**It counts profiles rather than comparing their configuration**, and that is the whole shape of the
rule. "No two profiles with equal config" would have caught none of the fourteen: almost every one
minted a *fresh temp directory* for a config key, which made its config map unequal to every other
**by construction** while buying nothing. Profiles that differ are the normal case even when the
duplication is pure accident, so difference cannot be the test. The count is — it is what the
metaspace is proportional to.

A failure names the offender *and every other unmarked profile in the module*, so one red build shows
the whole bill, prices the duplication, and gives the two repairs: merge it, or implement
`NecessaryTestProfileDuplication` and say in that class's javadoc why the two configurations cannot
be one. The marker's own javadoc carries the price list, so marking one is a purchase rather than a
silenced test; "it needs a different config map" is not a reason, since that was true of all fourteen.

Interfaces, abstract classes and anonymous classes are not counted: none can be named in a
`@TestProfile`, and a shared abstract base several profiles extend is the *repair* this rule asks
for. A module with no profile at all passes — the best state the rule describes, not a
misconfiguration.

`QuarkusTestProfile` is matched **by name**, the same trick as above, and it earns its keep twice
here: a compile dependency on `quarkus-junit5` would put the Quarkus test framework on every
consumer's classpath and pin the fleet's Quarkus version from an arch-rules release. The fixture
mirror in this module's test sources is where a Quarkus rename would surface.

## DatasourceBaselineRules

The datasource resilience baseline, as a build failure. Not an ArchUnit rule — it reads the service's
own MicroProfile config — but the same bargain, and a plain JUnit test:

```java
class DatasourceBaselineTest {
  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
```

It finds every datasource the service declares as `db-kind=postgresql` (named, default, or with a
quoted name) and demands all three lines on each:

```properties
quarkus.datasource.<name>.jdbc.driver=eu.wohlben.qits.db.PatientPgDriver
quarkus.datasource.<name>.jdbc.validate-on-borrow=true
quarkus.datasource.<name>.jdbc.acquisition-timeout=15S
```

A failure names the datasource, prints the exact line that is missing and what it costs to be
without it, and points at `docs/project-setup-quinoa-angular.md`. Every missing line is reported at
once. Datasources of another kind — h2 in a test — are left alone, and a URL over an unset
environment variable does not derail the scan.

**`acquisition-timeout` must be written down, not merely answerable.** It is the one line of the
three whose absence is invisible: Quarkus defaults it to `5S` — the very value the baseline replaces
— and reports that default like any other value, so a service that never wrote the line used to
pass. Measured on a live Quarkus 3.34.6 configuration, 2026-08-11: an unset `jdbc.max-size` answers
`50` and *appears among the property names*, from a source called `DefaultValuesConfigSource` at
ordinal `Integer.MIN_VALUE`. So neither the name nor the value decides it; where it came from does.
The rule now demands a declaration, from any source a person could have written, in the plain or the
profiled (`%prod.`) spelling. Every fleet service already writes `15S`, so this breaks nobody.

`assertBaseline(Config)` takes a configuration explicitly, for a test that wants to pin one shape.

The driver is named as a **string**, the same trick the ArchUnit rules use: this module depends on
neither qits-db-core nor a datasource, config carries names, and a bare clone builds with no platform
registry. The MicroProfile config API is a **provided** dependency — every Quarkus consumer already
has it on its test classpath.

---

# qits-auth-core

## What it is

Two tracks of identity, in one jar.

**Users** arrive through qits-gateway, which performs the login and injects
`X-Qits-User` and `X-Qits-Roles`. `ForwardAuthMechanism` turns those headers into a
`SecurityIdentity`. A service authenticates nothing itself; the header is
believed because the gateway strips every client-supplied `X-Qits-*` header
first. This pair was copy-pasted into eight services and is now in one place —
identical in behaviour, config keys included.

**Machines** arrive with a bearer token from qits-idp. The service validates it
with its own `quarkus-oidc`; this jar reads the result. `MachineAuth` answers
"does this caller hold a token for me, covering this project / workspace /
branch", behind a rollout gate so a service can ship the check before qits-idp
exists.

**It stays thin on purpose.** No `quarkus-oidc` dependency — validation is the
service's own choice of extension and config. This jar carries the mechanism,
the vocabulary, the claim checks and the gate.

### Public surface

| Class | |
| --- | --- |
| `ForwardAuthMechanism` | Reads `X-Qits-User` and the comma-separated `X-Qits-Roles`; no user header is anonymous, not a denial. |
| `ForwardAuthIdentityProvider` | Completes that request into an identity whose principal and roles are available to standard Jakarta security annotations. |
| `QitsClaims` | The claim names (`project`, `workspace`, `branch`) and the `*` that covers every value. Service ids are config, never constants. |
| `MachineIdentity` | Static, CDI-free reads of a validated token off a `SecurityIdentity`. |
| `MachineAuth` | The `require*` guards, and the rollout gate they sit behind. |

## Adoption

The same shape qits-ci-service uses for
`components/qits-eventstream/qits-eventstream-javalib`: a nested submodule the
consuming reactor builds in place. The submodule takes the repository's name;
the checkout directory keeps the short one, because it is also the reactor
module path. From the consuming service's repo root:

```sh
git submodule add --name qits-integrations-quarkus-javalib \
  https://github.com/QuicklyIterateTheSoftware/qits-integrations-quarkus-javalib.git \
  qits-integrations-quarkus
git config -f .gitmodules submodule.qits-integrations-quarkus-javalib.ignore all
git config -f .gitmodules submodule.qits-integrations-quarkus-javalib.update merge
git submodule set-branch --branch main qits-integrations-quarkus
```

`--name` is not optional — see the superproject's CLAUDE.md for why.

Then add the directory to the reactor, ahead of the module that uses it:

```xml
<modules>
    <module>qits-integrations-quarkus</module>
    ...
    <module>service</module>
</modules>
```

The directory is this repo's aggregator pom, so Maven picks up `qits-auth-core`
— and anything added here later — on its own. Manage the version in the
service's parent, beside the other reactor modules:

```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>qits-auth-core</artifactId>
    <version>${project.version}</version>
</dependency>
```

…then depend on it version-less from the `service` module.

Finally, in the service:

1. **Delete** `…/security/ForwardAuthMechanism.java` and
   `ForwardAuthIdentityProvider.java`, plus the tests that only re-asserted them
   (`ForwardAuthTest`, `NoDevUserProfile`, `IdentityEchoResource`) — that suite
   moved here with the code.
2. **Delete** the `qits.auth.forward.*` keys from the service's own
   `META-INF/microprofile-config.properties`. This jar ships them at the same
   ordinal (100), and two files carrying one key make the winner arbitrary.
3. A fresh clone now needs `git submodule update --init` before `./mvnw verify`.
   An uninitialised submodule fails as maven's `Child module … does not exist`,
   before a line compiles.

## Config

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.auth.forward.user-header` | `X-Qits-User` | The header qits-gateway asserts. |
| `qits.auth.forward.dev-user` | `dev` under `%dev`/`%test`, unset otherwise | Synthetic identity when no header arrives. Ignored by a prod build even if it leaks in via env. |
| `qits.auth.machine.required` | `false` | The rollout gate. |
| `qits.auth.machine.audience` | unset | This service's own id, e.g. `qits-ci`. Required once the gate is on. |
| `qits.auth.machine.platform-audience` | `qits-platform` | The one audience shared by every token on the platform (rulings 2026-09-13). A token naming this value passes every check that naming `qits.auth.machine.audience` would. Blank turns this off. |

Defaults ship in this jar's `META-INF/microprofile-config.properties`
(ordinal 100), below the service's `application.properties` (250) and env (300).

### Bearer validation and token fetching

The service adds these itself:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-oidc</artifactId>
</dependency>
<dependency>
    <!-- only if the service also CALLS another service -->
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-oidc-client</artifactId>
</dependency>
```

Validating inbound bearers (qits-ci shown; substitute the service's own id):

```properties
qits.auth.machine.audience=qits-ci

quarkus.oidc.auth-server-url=http://qits-idp:8080/idp
quarkus.oidc.application-type=service
# Discovery off: the fetch is internal on qits-net while the issuer string is a
# public URL, so the JWKS path is given rather than looked up. It is joined onto
# auth-server-url, so it is `jwks`, not `/idp/jwks`.
quarkus.oidc.discovery-enabled=false
quarkus.oidc.jwks-path=jwks
quarkus.oidc.token.audience=${qits.auth.machine.audience}
```

Set `quarkus.oidc.token.issuer` when the two diverge — they do the day the idp
serves users at a public URL and the services keep fetching direct.

Bearer validation sits *beside* forward-auth, it does not replace it: a request
with no `Authorization` header falls through to the header mechanism and stays
user traffic.

Services reach qits-idp direct on qits-net, never through the gateway.

Fetching outbound tokens (qits-ci calling qits-cd):

```properties
quarkus.oidc-client.auth-server-url=http://qits-idp:8080/idp
quarkus.oidc-client.discovery-enabled=false
quarkus.oidc-client.token-path=token
quarkus.oidc-client.client-id=qits-ci
quarkus.oidc-client.credentials.secret=${QITS_CI_CLIENT_SECRET}
quarkus.oidc-client.grant.type=client
quarkus.oidc-client.grant-options.client.audience=qits-cd
```

`quarkus-oidc-client` caches and refreshes, so an idp restart pauses new-token
issuance and nothing else — validation runs off cached JWKS.

## Enforcing

Inject `MachineAuth` and call one `require*` method where the decision belongs.
It works the same from a JAX-RS filter and from a resource method.

```java
@Inject MachineAuth machineAuth;

@POST
@Path("/post-receive")
public Response postReceive(@Valid PostReceiveEvent event) {
  machineAuth.requireProject(event.repoId());
  ...
}
```

| Call | Demands |
| --- | --- |
| `require()` | A machine token addressed to this service. |
| `requireProject(p)` | …and a `project` claim equal to `p`. |
| `requireWorkspace(w)` | …and a `workspace` claim equal to `w`. |
| `requireBranch(b)` | …and a `branch` claim equal to `b`. |
| `requireClaim(name, v)` | …and any claim named in `QitsClaims`. |
| `permits(name, v)` | The same decision as a boolean, for filtering a list. |
| `enforced()` | Whether the gate is on. Log the posture with it; do not branch on it. |

An absent claim is a mismatch: a token never granted a `project` may not act on
one.

A token also passes when its `aud` names `qits.auth.machine.platform-audience`
(default `qits-platform`) instead of this service's own
`qits.auth.machine.audience` — one audience for every token on the platform
(rulings 2026-09-13). A blank `qits.auth.machine.platform-audience` turns this
off, leaving only the service's own audience — today's behaviour.

### The wildcard

A claim value of `*` (`QitsClaims.ANY`) covers every value. A client granted
`project=*` passes `requireProject(anything)`.

That is how a service acting across all of something holds its claim rather than
being granted a list that grows: qits-githost holds every project's
repositories, so its token says `project=*`.

The wildcard is read on the token side only. Passing `"*"` as the *target* asks
about a thing named `*` and gets the ordinary equality answer, so no call site
can widen its own check with it. And a wildcard on one claim grants nothing on
another — `project=*` still fails `requireWorkspace(...)`.

A failure throws `UnauthorizedException` (401) when no machine token was
presented, `ForbiddenException` (403) when one was but it does not cover the
target. Quarkus REST maps both — no exception mapper needed.

Reach for `MachineIdentity`'s static methods when the decision is more than an
equality check; they take the identity explicitly and need no CDI.

Spell claim names from `QitsClaims` (`PROJECT`, `WORKSPACE`, `BRANCH`, `ANY`). A
mistyped claim name reads as "claim absent", which is a silent pass on an
unenforced path.

**Service ids are not in `QitsClaims`, and none may go back in.** Every service
is deployed once per environment as `<env>-qits-<app>`, so an id is deployment
knowledge: a service reads its own from `qits.auth.machine.audience` and its
peers' from injected config. `CI`, `CD`, `ARTIFACTS`, `WORKSPACES` and `GATEWAY`
were constants here and are gone with the last of the platform-scoped services.

### The gate

`qits.auth.machine.required` is `false` everywhere until qits-idp is deployed.
Off, every `require*` returns at once and the endpoint behaves exactly as it
does today — network trust, no bearer needed. That is what lets enforcement code
ship first and switch on later, one service at a time, with an env var.

There is no third state. Turning the gate on without
`qits.auth.machine.audience` fails at startup rather than accepting a token
minted for another service.

### Phase-1 call sites

| Service | Endpoint | Call |
| --- | --- | --- |
| qits-ci | `POST /ci/api/events/*` | `requireProject(<the event's project>)` — replaces `CiTokenFilter` / `X-CI-Token`. |
| qits-cd | `POST /cd/api/events/build-succeeded` | `require()`. |
| qits-artifacts | JAX-RS writes under `repositories`, `store`, `gc`, `mirror-upstreams` | `require()` — replaces `ArtifactsTokenFilter` / `X-Artifacts-Token`. `/artifacts/git/*` and `/v2/*` are unchanged. |

Each service sets `qits.auth.machine.audience` to its own id, so `require()`
already means "a token minted for me".

---

# qits-environment-core

## What it is

The environment tier as an ambient value. The platform has environments (`dev`, `prod`) and one
platform plane serving all of them; qits-deployments injects `QITS_ENVIRONMENT=<name>` into every
environment-tier service and deliberately nothing into a platform-tier one. This module makes that
fact travel:

- **`EnvironmentClientFilter`** stamps `X-Qits-Environment` on every outgoing REST-client request:
  `qits.environment` (the MicroProfile reading of `QITS_ENVIRONMENT`), or the literal `platform`
  where a deployment injects none — a process the deployer gives no tier is, by the platform's own
  definition, serving every tier. A header the caller set itself wins.
- **`EnvironmentServerFilter`** reads the header on the way in and holds it in
  **`CallerEnvironment`** for the resource method, so a platform service learns which tier is
  calling without reading headers itself. Absent or blank is `null` — an unstamped caller is
  *unknown*, never assumed `platform`, because an absence of a claim is not a claim.
- **`EnvironmentHeader`** is the constants: `NAME`, `PROPERTY`, `PLATFORM`.

Both filters are `@Provider`-discovered through the jandex index: a consumer registers nothing, and
a consumer without `quarkus-rest`/`quarkus-rest-client` never instantiates them. The pom carries
the JAX-RS, annotation and MP-config **API jars only** — never `quarkus-rest`, which would bolt an
HTTP server onto every consumer.

```xml
<dependency>
    <groupId>eu.wohlben.qits</groupId>
    <artifactId>qits-environment-core</artifactId>
</dependency>
```

The header is inside the edge's reserved `X-Qits-*` namespace on purpose: qits-platform-edge strips
every client-supplied `X-Qits-*` header at the outer door, so an outside caller cannot claim a
tier. A caller building requests by hand (`java.net.http.HttpClient`) stamps the same header itself
— the snippet is in `EnvironmentHeader`'s javadoc.

**The same property is stamped onto every published event** by
`components/qits-eventstream/qits-eventstream-javalib`
(`EventEnvelope.environment`, same `platform` fallback), whose extraction rule forbids importing
`EnvironmentHeader` — the string `qits.environment` is deliberately spelled in both repositories,
so grep both on a rename. `CallerEnvironment` follows `CausationScope`'s thread discipline whole:
restore-not-clear, `remove()` for null, a plain `ThreadLocal` that does not follow work — capture
`current()` before handing work to an executor and re-establish it with `with(...)`.

---

# qits-service-mock

Recording mocks of platform services for cross-service integration tests — the far side of any
service-to-service interaction, assertable on both ends: the consumer under test acted on the
response, and the mock's recordings prove this side served it.

```xml
<dependency>
    <groupId>eu.wohlben.qits</groupId>
    <artifactId>qits-service-mock</artifactId>
    <version>…</version>
    <scope>test</scope>
</dependency>
```

Deliberately **not a Quarkus module**: it backs `@QuarkusIntegrationTest` test profiles, which run
in a plain JVM before and beside the launched application. JDK `com.sun.net.httpserver` + JCA, one
Jackson dependency.

## MockService — the generic core

Faking a service is usually **no code at all**: stub the routes the consumer will call, point the
consumer's config at `baseUrl()`, assert the recordings afterwards. Unknown paths answer 404 *and
are recorded* — "the consumer called the wrong path" is as assertable as the happy path.

```java
MockService projects = MockService.start("qits-projects");
projects.stub("GET", "/projects/api/names/qits/my-repo", Map.of("repositoryId", id));
// ... boot the consumer against projects.baseUrl(), drive it ...
projects.recordedRequests();   // did it resolve the name? what did it send?
```

A recording is `RecordedRequest(method, path, query, status, at, headers)`. `query` is the **raw**
query string — verbatim, never decoded or parsed, `null` when the URI carried none, and never part
of `path`. `status` is what this side answered: the matched stub's status, or 404 for a route no
stub matched — which is what lets a recording alone say *which* answer the consumer acted on (and
what a diagram generated from recordings labels its edges with).

A service only earns a named class when it has behavior canned JSON cannot fake. There is one so
far: `idp.MockIdp` below. Write the next one the same way — `MockService` plus only the genuinely
service-specific part, in its own subpackage.

**The `QuarkusTestProfile` pattern**: a test profile is instantiated in more than one classloader,
so `ensureStarted(name)` starts once per JVM per name and parks the port in a system property;
`attach(name)` — from any classloader — rebuilds a handle. Recordings are read back over the
mock's own `/__mock/requests` control endpoint (itself excluded from recording), so every handle
sees the same live list. Only the owning instance can `stub(...)`.

## idp.MockIdp — the one specialization

The mock of qits-platform-idp: a `MockService` plus key material. It generates an RSA keypair,
stubs `GET /idp/jwks` (shaped exactly like the real `idp/control/Jwks`, leading-zero stripping
included) and a minimal `/idp/.well-known/openid-configuration`, and mints RS256 tokens signed by
that keypair. The compact JWS is hand-assembled (~40 lines, JCA only) and its correctness pinned
by a self-test verifying through jose4j — the library quarkus-oidc itself is built on.

```java
MockIdp idp = MockIdp.start();                       // or ensureStarted()/attach() — same pattern
String url = idp.baseUrl();                          // -> quarkus.oidc.auth-server-url
String token = idp.token()
    .subject("qits-ci").audience("dev-qits-githost").groups("qits:system").mint();
idp.recordedRequests();                              // did the service fetch /idp/jwks?
idp.service();                                       // the underlying MockService, for more stubs
```

Negative-test levers on the builder: `signedByUnknownKey()` (a stranger's signature),
`kid("no-such-kid")`, `ttl(Duration.ofMinutes(-5))` (already expired), a wrong `audience(...)`.
`ensureStarted()` additionally parks the encoded keypair in system properties, so an attached
handle signs with the same key the served JWKS published.
