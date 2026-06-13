---
title: Caddy
description: Configure Caddy as a reverse proxy in front of calit.
---

[Caddy](https://caddyserver.com/) is the simplest option: it provisions and renews TLS certificates automatically via Let's Encrypt, and it forwards `X-Forwarded-Proto`, `X-Forwarded-For`, and `Host` to upstream services by default.

## Caddyfile

```caddy
book.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Replace `book.example.com` with your domain. If calit runs in a Docker container on the same host replace `127.0.0.1:8080` with the container address (e.g. `calit-app:8080` if both containers share a Docker network).

That is the entire configuration. Caddy handles:

- Automatic TLS certificate provisioning and renewal (Let's Encrypt or ZeroSSL)
- HTTP → HTTPS redirect
- `X-Forwarded-Proto: https` forwarding — satisfying calit's [secure-cookie requirement](/calit/installation/reverse-proxy/overview/#why-the-proxy-configuration-matters--secure-login-cookie)
- `X-Forwarded-For` and `Host` header forwarding

No extra header directives are needed.

## Set APP_BASE_URL

In calit's `.env`:

```
APP_BASE_URL=https://book.example.com
```

## Starting Caddy

```bash
caddy run --config /path/to/Caddyfile
```

Or with Docker:

```yaml
services:
  caddy:
    image: caddy:latest
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy-data:/data
    restart: unless-stopped

volumes:
  caddy-data:
```

The `caddy-data` volume persists the issued certificates across container restarts.
