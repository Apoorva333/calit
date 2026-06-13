---
title: Traefik
description: Configure Traefik as a reverse proxy in front of calit using Docker labels.
---

[Traefik](https://traefik.io/) integrates directly with Docker: add labels to the `app` service in `docker-compose.yml` and Traefik discovers the route automatically. It forwards `X-Forwarded-Proto`, `X-Forwarded-For`, and `Host` to backends by default, satisfying calit's [secure-cookie requirement](/calit/installation/reverse-proxy/overview/#why-the-proxy-configuration-matters--secure-login-cookie).

## docker-compose labels

Add the following labels to the `app` service in your `docker-compose.yml`:

```yaml
  app:
    # ...existing app service config...
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.calit.rule=Host(`book.example.com`)"
      - "traefik.http.routers.calit.entrypoints=websecure"
      - "traefik.http.routers.calit.tls.certresolver=letsencrypt"
      - "traefik.http.services.calit.loadbalancer.server.port=8080"
```

Replace `book.example.com` with your domain.

## Prerequisites

- Traefik must be running with a `websecure` entrypoint (port 443) and a certificate resolver named `letsencrypt` already configured on the Traefik instance.
- The `app` service and the Traefik container must share the same Docker network so Traefik can reach the calit container.
- Remove or do not expose port 8080 to the host in the `app` service — let Traefik route all traffic.

A minimal Traefik static configuration (`traefik.yml`) that defines the required certresolver:

```yaml
entryPoints:
  web:
    address: ":80"
    http:
      redirections:
        entryPoint:
          to: websecure
          scheme: https
  websecure:
    address: ":443"

certificatesResolvers:
  letsencrypt:
    acme:
      email: you@example.com
      storage: /letsencrypt/acme.json
      httpChallenge:
        entryPoint: web
```

## Set APP_BASE_URL

In calit's `.env`:

```
APP_BASE_URL=https://book.example.com
```

## Header forwarding

Traefik passes `X-Forwarded-Proto: https`, `X-Forwarded-For`, and `Host` to the backend by default when the router uses a TLS entrypoint. No additional middleware is needed for calit to receive these headers correctly.
