---
title: nginx
description: Configure nginx as a reverse proxy in front of calit.
---

The server block below terminates TLS, redirects HTTP to HTTPS, and forwards all required headers to calit on port 8080.

## Server block

```nginx
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name book.example.com;

    ssl_certificate     /etc/letsencrypt/live/book.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/book.example.com/privkey.pem;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   X-Forwarded-Host  $host;
    }
}

server {
    listen 80;
    server_name book.example.com;
    return 301 https://$host$request_uri;
}
```

Replace `book.example.com` with your domain and update the certificate paths accordingly. If your calit container is on a different host or Docker network, replace `127.0.0.1:8080` with the correct address.

## The critical line

```nginx
proxy_set_header   X-Forwarded-Proto $scheme;
```

This line sets `X-Forwarded-Proto: https` when nginx receives the request over TLS. calit reads this header (via `quarkus.http.proxy.proxy-address-forwarding=true`) and marks the login session cookie `Secure`. Without it the cookie will not carry the `Secure` flag and browsers will refuse to send it over HTTPS, breaking login.

## Obtaining a certificate

[Certbot](https://certbot.eff.org/) with the nginx plugin is the standard approach:

```bash
certbot --nginx -d book.example.com
```

Certbot writes the certificate paths and adds an HTTPS redirect automatically. Review the result against the server block above.

## Set APP_BASE_URL

In calit's `.env`:

```
APP_BASE_URL=https://book.example.com
```
