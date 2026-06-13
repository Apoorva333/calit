---
title: Reverse proxy — overview
description: Run calit behind a TLS-terminating reverse proxy correctly.
---

calit listens on **plain HTTP on port 8080** inside its container. TLS is terminated by a reverse proxy in front of it. This is the standard, recommended deployment model.

## Why the proxy configuration matters — secure login cookie

:::caution[Secure cookie requires X-Forwarded-Proto]
calit sets the `Secure` flag on the login session cookie only when it detects the request arrived over HTTPS. Detection uses `request.isSSL()`, which checks the actual transport — not the public URL. Behind a TLS-terminating proxy the transport between proxy and app is plain HTTP, so `isSSL()` returns `false` and the cookie is **not** marked `Secure`.

calit works around this by enabling `quarkus.http.proxy.proxy-address-forwarding=true` in its production profile. This tells the Quarkus HTTP layer to trust the `X-Forwarded-Proto` header and derive SSL status from it. The login cookie is then correctly marked `Secure`.

**Consequence:** your proxy **must** send `X-Forwarded-Proto: https` (or the equivalent) on every request. If it does not, the browser will refuse to send the cookie over HTTPS and logins will break.
:::

## Required forwarded headers

Every proxy configuration must pass these headers to calit:

| Header | Purpose |
|---|---|
| `X-Forwarded-Proto` | Tells calit the public scheme (`https`). Required for the secure cookie. |
| `X-Forwarded-For` | Real client IP, used for rate limiting and logging. |
| `Host` | Original request hostname, used to build redirect URLs. |

## Set APP_BASE_URL

Set the `APP_BASE_URL` environment variable to your **public HTTPS URL**, for example:

```
APP_BASE_URL=https://book.example.com
```

calit uses this value to build absolute links (booking confirmation emails, Google OAuth redirect URIs, etc.).

## Hardening: restrict trusted proxies

`proxy-address-forwarding=true` instructs calit to trust forwarded headers from **any** source. If the container port (8080) is reachable directly — bypassing the proxy — a client could forge `X-Forwarded-Proto: https` and circumvent security checks.

To prevent this, restrict which upstream IPs are allowed to set forwarded headers:

```
QUARKUS_HTTP_PROXY_TRUSTED_PROXIES=<proxy CIDR>
```

For example, if your proxy container is on the `172.20.0.0/16` Docker network:

```
QUARKUS_HTTP_PROXY_TRUSTED_PROXIES=172.20.0.0/16
```

When the container port is not exposed to the public internet (the typical Docker Compose setup where only the proxy port is published), this is optional but still recommended.

## WebSocket configuration

No WebSocket configuration is needed. calit ships no runtime WebSocket connections and no single-page-app — it is entirely server-rendered HTML.

## Proxy-specific guides

- [Nginx Proxy Manager](/calit/installation/reverse-proxy/nginx-proxy-manager/) — GUI-based setup, good for homelab use
- [nginx](/calit/installation/reverse-proxy/nginx/) — manual server block
- [Caddy](/calit/installation/reverse-proxy/caddy/) — automatic TLS, minimal configuration
- [Traefik](/calit/installation/reverse-proxy/traefik/) — Docker-label-driven, integrates with docker-compose
