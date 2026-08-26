# Access gateway — one front door, basic auth (design)

**Date:** 2026-08-25 · **Status:** approved by Calvin; phase 1 in progress
**Decides** the question flagged in `access-model.md` (old `.home`/WireGuard plan vs deployed
tailnet): the answer is **real subdomains of `experimentalneutron.com` over both paths**, with
auth added at the ingress edge.

## Goal

One place to land (**Olympus**, a portal listing every console) and one place to authenticate
(**Authelia** at the Traefik edge) — after login, every UI opens without further prompts.
Replaces per-device hosts-file `.tailscale` names, which don't scale (per-device maintenance,
no phones) and can't carry a proper SSO cookie or real TLS.

## Architecture

```
  browser ── https://<svc>.home.experimentalneutron.com ──► Traefik (:61642 NodePort)
                                                              │ forwardAuth middleware
                                                              ▼
                                              Authelia (session cookie, domain-wide SSO,
                                              file-backed user + optional TOTP)
                                                              │ authed
                                                              ▼
                                              the UI's Service (unchanged apps)
```

- **Zero app changes**: security lands at the ingress; every future UI inherits it by adding
  the middleware to its ingress.
- **Portal = Olympus**: [Homepage](https://github.com/gethomepage/homepage) (YAML tiles, health
  checks), themed to `docs/ux-standards.md`, at `olympus.home.experimentalneutron.com`, behind
  the same gate — the default landing page.
- **Honest scope**: the tailnet already provides device-level auth; Authelia adds browser-level
  login + defense-in-depth *today*, and the OIDC provider for the deferred multi-user era. It
  is cheap at the edge, not a response to a large present threat.

## DNS (phase 1 — the foundation)

Names: `olympus | dionysus | hermes | apollo | artemis | demeter`.home.experimentalneutron.com
(one entry per service, extended as services land — same declarative ethos as `vlans.yml`).

- **LAN path**: pfSense Unbound **host overrides** → `192.168.1.70` (mimir's LAN IP, where the
  Traefik NodePort answers). Managed declaratively via the existing ansible flow
  (`network/pfsense/`). Every LAN device — phones included — resolves without hosts files
  (LAN DHCP already hands out pfSense as DNS).
- **Tailnet path** (away from home), one of two — Calvin's pick:
  1. **Tailscale split-DNS**: admin console → DNS → add `home.experimentalneutron.com` with
     nameserver `192.168.1.1`. Requires mimir to advertise the `192.168.1.0/24` subnet route
     (checked during rollout). Keeps all records private.
  2. **Public wildcard**: a single `*.home.experimentalneutron.com A 100.107.133.54` record at
     the DNS provider. Works on every tailscale-running device anywhere, zero pfSense/tailnet
     config; publishes a (harmless, unroutable-to-outsiders) 100.x IP publicly. The existing
     public `mimir.experimentalneutron.com → 192.168.1.70` record shows this pattern in use.
- `.tailscale` hosts entries stay working during migration; retire them at phase 3.

## Phases

1. **DNS foundation** ◄ in progress — pfSense overrides via ansible + Calvin's tailnet-path
   pick. New names live alongside `.tailscale`; nothing breaks.
2. **Authelia** — deploy (ns `authelia`; file backend, one user, TOTP optional), Traefik
   `Middleware` (forwardAuth) CRD, protect **hermes-ui** as the pilot.
3. **Roll out** — middleware on all UI ingresses; ingress hosts move to the new names;
   retire hosts-file entries.
4. **Olympus portal** — Homepage deployment behind the gate, tiles for every console + health.
5. *(later)* Wildcard TLS via DNS-01 (pays off the parked QuObjects/tailnet cert debt properly)
   and per-app OIDC when multi-user arrives.

## Non-goals (now)

Internet exposure (everything stays LAN/tailnet); per-app logins; Keycloak/Authentik-scale
IdP; mTLS between services; replacing tailscale.

## Naming

Portal: **Olympus** (where the gods live). Auth guard: Authelia the product — if it ever
becomes a bespoke service, the pantheon name is **Heimdall** (Norse = infra, the watchman of
the bridge).
