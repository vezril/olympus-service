# Olympus — the constellation's front door (service)

**Status:** project seed (2026-08-25). Docs placed by the Codex session on Calvin's request.
**Decision recorded:** Olympus is a **bespoke service + UI pair** in the per-service pattern
(olympus-service + olympus-ui) — superseding the design note's original lean toward an
off-the-shelf Homepage deployment. The rest of `DESIGN-access-gateway.md` (Authelia at the
Traefik edge, real-domain DNS, phases) still stands unless this project changes it.

## What Olympus is

The single place Calvin lands: a portal listing every console (dionysus, hermes, apollo,
artemis, demeter, and whatever Hera/Poseidon/Ares/… become) with live health, behind the
constellation's one login. `olympus.home.experimentalneutron.com` (pfSense DNS override
already provisioned in codex `network/pfsense/vlans.yml`; tailnet path per the design note).

## Suggested scope (v1) — for this project's session to design properly

- **Service registry**: the list of consoles/services (name, URL, mark, namespace) — start
  static-config (the codex apps/ dir is the source of truth today), later maybe live from k8s.
- **Health aggregation**: poll each service's /health (or its console's BFF health) server-side;
  the portal renders Live/Down pills without the browser fanning out.
- **Auth posture** (OPEN — decide with Calvin): the design note puts auth in Authelia at the
  Traefik edge (zero app changes, SSO cookie for every console). Olympus then just SITS behind
  it like everything else. The alternative — Olympus absorbing the auth role itself — means
  session/identity work in-app and per-console enforcement questions. Recommendation: keep
  Authelia at the edge; Olympus = portal only.

## Constellation conventions that apply

- Deploys: git (codex repo) = source of truth; manual mirrored-values helm (no Flux yet —
  k3s 1.21). Codex hosts `apps/olympus/` when there's a release.
- The backend stack precedent is Scala 3 + Pekko (`new-scala-pekko-service` skill scaffolds it)
  — but Olympus v1 may not need a backend beyond the UI's BFF at all (health polling can live
  in the Next.js BFF). Decide before scaffolding: **maybe olympus-ui alone is v1** and this
  repo waits.
- Broker/queue: only if Olympus ever needs async (unlikely v1).
- Correlation/tracing: adopt the constellation standard if a backend exists (lexicon
  CorrelationNames, mint-or-adopt at edges).

## Related reading (codex repo)

- `DESIGN-access-gateway.md` (here) — the approved architecture + phases.
- codex `clusters/homelab/README.md` — deploy conventions + non-codex tenants.
- codex `docs/ux-standards.md` — the UI/UX standards (copied into olympus-ui).

---

## What it is now

Olympus is a service + UI pair. **This repo owns the console registry and the
health fan-out**; [olympus-ui](https://github.com/vezril/olympus-ui) renders it.

```
browser ──► olympus-ui BFF ──► olympus-service ──► each console's Service
         (same origin only)   (this repo)         (in-cluster, GET /)
```

Scope decided 2026-08-25: the alternative was leaving the fan-out in the UI's BFF
and this repo a skeleton. Registry + aggregation won, so there is one source of
truth for what a console is.

## Endpoints

| | |
|---|---|
| `GET /health` | this service's own liveness — what the k8s probe hits |
| `GET /consoles` | the registry |
| `GET /health/consoles` | the aggregated fan-out the portal renders |
| `GET /` | 307 to `/health`, so a root readiness probe stays honest |

Never point a k8s probe at `/health/consoles`: a console being down would restart
Olympus, which is not the problem.

## Stack

Scala 3.3.4 · Apache Pekko HTTP · circe · Typesafe Config · ScalaTest ·
scalafmt · sbt-native-packager · sbt-dynver (version comes from the git tag).

Laid out as `api` / `application` / `domain`.

## Run it

```bash
sbt run
```

Off-cluster every console reports **Down** — it is probing in-cluster DNS. To
exercise the Live path, run the packaged launcher against an override registry:

```bash
sbt stage && target/universal/stage/bin/olympus-service -Dconfig.file=local.conf
```

`local.conf` should `include classpath("application.conf")` and then redefine
`olympus.consoles` with reachable `health-url` values. Note `sbt -Dconfig.file=…`
does **not** work: `run` is forked and the property lands on the wrong JVM.

The green gate, which CI also runs:

```bash
sbt scalafmtCheckAll compile test
```

## How health works

- Default probe: `GET /` on `http://<service>.<namespace>.svc.cluster.local/` —
  the same target the console's own readiness probe answers, so 200–399 passes.
  Override per console with `health-url`.
- `planned` consoles are never probed. They are named, not built, and a permanent
  red pill would be a lie.
- **A dead console is data, not a failed report.** One unreachable console never
  takes the fan-out down with it; its entry carries the reason instead.
- The registry loads **strictly** at startup: a duplicate id, an unknown status or
  a missing field fails the service rather than silently dropping a console from
  the portal.

## Configuration

| Variable | Default |
|---|---|
| `OLYMPUS_HTTP_HOST` | `0.0.0.0` |
| `OLYMPUS_HTTP_PORT` | `8080` |
| `OLYMPUS_DOMAIN` | `home.experimentalneutron.com` |
| `OLYMPUS_HEALTH_TIMEOUT` | `3 seconds` |

The registry itself lives in `src/main/resources/application.conf`. The chart can
override it wholesale via a ConfigMap layered on with `-Dconfig.file`; changing it
rolls the pods through a checksum annotation.

## Image

`calvinference/olympusservice` — the Docker Hub account is `calvinference`, not
the GitHub org. `eclipse-temurin:21-jre-alpine`, non-root uid 1001.

```bash
sbt Docker/publishLocal
```

Note the runtime stage installs `bash`: native-packager's launcher is a bash
script and `jre-alpine` ships none, which exits 127 at runtime rather than at
build time. CI starts the built image and waits for `/health` so that class of
bug cannot ship.

## Chart and deploy

Chart at [`deploy/charts/olympus-service`](deploy/charts/olympus-service).
The authoritative record of what is deployed is codex `apps/olympus/` — there are
deliberately no Flux manifests here, because a second copy of the deploy values
that nothing keeps honest is how they drift. **No ingress by default and
it should stay that way** — only olympus-ui's BFF talks to this service, and
exposing it would publish the registry and every in-cluster probe target.

Point the UI at it:

```
OLYMPUS_SERVICE_URL=http://olympus-service.olympus.svc.cluster.local
```

No Flux on this cluster — deploys are a manual `helm upgrade -f <mirrored-values>`
from the tagged chart, never a bare `--set`. Releases are tags on `main`
(`vX.Y.Z`); `release.yml` refuses a tag that is not an ancestor of main, refuses a
version already on Docker Hub, and skips the publish when `DOCKERHUB_*` are absent.
