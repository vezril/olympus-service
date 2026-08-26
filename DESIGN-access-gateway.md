# Access gateway — one front door, basic auth (design)

**Date:** 2026-08-25 · **Updated:** 2026-08-26 · **Status:** approved by Calvin.
Phase 1 in progress (Tailscale going onto the router); phases 2 and 4 built — Authelia
is deployed but gates nothing until phase 1 lands, and the portal shipped as a bespoke
service+UI pair rather than Homepage.
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
- **Tailnet path** (away from home): **Tailscale split-DNS — decided 2026-08-26.**
  Admin console → DNS → add `home.experimentalneutron.com` with nameserver `192.168.1.1`.
  The rejected alternative was a public `*.home.experimentalneutron.com A 100.107.133.54`
  record: zero tailnet config, but it publishes the tailnet IP and adds a second place where
  these names are defined. Reconsidered 2026-08-26 when the mimir route turned out to be
  unreachable: it needs no subnet route, no console DNS and no shell, and a more specific
  wildcard cleanly overrides the existing catch-all. It stays rejected only because Tailscale
  on the router solves the same problem without publishing the service names — the IP is
  harmless (CGNAT, unroutable to outsiders), the *names* are the leak. If the router path ever
  fails, this is the fallback and it is a good one.

  Two things checked on 2026-08-26 that the rollout depends on:

  1. **The subnet route carrier is the ROUTER (odin), not mimir — revised 2026-08-26.**

     Split-DNS points tailnet devices at `192.168.1.1`, which they cannot reach until
     `192.168.1.0/24` is advertised **and approved**. The first plan put that on mimir. It
     cannot go there: mimir's Tailscale is the QNAP QPKG (1.40.0), whose web UI exposes only
     exit-node advertising — no route controls — and the NAS refuses key-based SSH, so there is
     no CLI to reach either. `tailscale status` confirms `PrimaryRoutes: None`; the `0.0.0.0/0`
     in its AllowedIPs is the exit-node offering, not a subnet route.

     **Install Tailscale on pfSense instead** (System → Package Manager → Tailscale; VPN →
     Tailscale → advertise `192.168.1.0/24`; approve in the admin console). This is better than
     the original plan rather than a workaround: it puts the tailnet node on the machine that
     already IS the resolver, so the Unbound host overrides declared in codex
     `network/pfsense/vlans.yml` become reachable from the tailnet with nothing else moving. It
     also makes the whole LAN reachable by name — proxmox, home-assistant, radarr — instead of
     just mimir, and leaves mimir's missing route UI irrelevant.

     Two cautions: accepting routes is per-client (iOS uses them automatically; macOS and Linux
     need `--accept-routes`), and this adds a package to the box everything depends on, so take
     a `config.xml` snapshot first.

  2. **`*.experimentalneutron.com` is already a public wildcard → `51.79.68.202`** (an OVH
     host; zone served by registrar-servers.com). Every name under it resolves there today,
     including `olympus.home.experimentalneutron.com` and names that do not exist. Split-DNS
     overrides this *for tailnet devices only*. So "keeps all records private" is true of the
     new records, but the catch-all already answers publicly for anything else — worth
     deciding separately whether that wildcard should stay.
- `.tailscale` hosts entries stay working during migration; retire them at phase 3.

## Phases

1. **DNS foundation** ◄ in progress — LAN half is **done**: every console name is declared
   in codex `network/pfsense/vlans.yml` under `dns_host_overrides` → `192.168.1.70`.
   Tailnet half is **decided (split-DNS, route carried by pfSense)** and in progress.
   New names live alongside `.tailscale`; nothing breaks.

   Why this phase matters more than it looks: `*.tailscale` is not DNS at all — those are
   per-machine `/etc/hosts` entries (`nslookup olympus.tailscale` → NXDOMAIN). **No
   constellation UI resolves on a phone today**, tailnet membership notwithstanding, because
   iOS has no editable hosts file. That affects all eight consoles equally, and this phase is
   the single fix for every one of them.
2. **Authelia** — **deployed 2026-08-26, protecting nothing yet (on purpose).** Authelia
   4.39.0 in ns `authelia`, file backend, sqlite on a PVC, and the Traefik forwardAuth
   `Middleware` applied but referenced by no ingress. codex `apps/authelia/`.

   It cannot gate anything until phase 1 lands, and that is not a guess — the running pod
   was asked: a forwarded host of `hermes.home.experimentalneutron.com` gets `302` to the
   auth portal, `hermes.tailscale` gets `400`, refused. The session cookie needs a real
   parent domain and `.tailscale` is a single-label pseudo-TLD.

   Outstanding on Calvin: the user password hash (`authelia-users` ships a placeholder — it
   must not pass through a chat log). Then protect **hermes-ui** as the pilot.
3. **Roll out** — middleware on all UI ingresses; ingress hosts move to the new names;
   retire hosts-file entries.
4. **Olympus portal** — **done 2026-08-26, and NOT Homepage.** Built as a bespoke pair
   instead: `olympus-service` owns the console registry and health fan-out, `olympus-ui` is
   the portal. Live at `olympus.tailscale:61642`, god marks on the tiles, and a read-only
   constellation board at `/board` rendered from codex's `constellation.yaml`. Behind the
   gate once phase 3 attaches the middleware.
5. *(later)* Wildcard TLS via DNS-01 (pays off the parked QuObjects/tailnet cert debt properly)
   and per-app OIDC when multi-user arrives.

## Non-goals (now)

Internet exposure (everything stays LAN/tailnet); per-app logins; Keycloak/Authentik-scale
IdP; mTLS between services; replacing tailscale.

## Naming

Portal: **Olympus** (where the gods live). Auth guard: Authelia the product — if it ever
becomes a bespoke service, the pantheon name is **Heimdall** (Norse = infra, the watchman of
the bridge).
