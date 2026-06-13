---
title: Nginx Proxy Manager
description: Configure Nginx Proxy Manager to front calit with automatic TLS.
---

[Nginx Proxy Manager](https://nginxproxymanager.com/) (NPM) is a GUI-based reverse proxy built on nginx. It is a common homelab choice because it manages Let's Encrypt certificates automatically.

NPM automatically forwards `X-Forwarded-Proto`, `X-Forwarded-For`, and `Host` to all proxy hosts, satisfying calit's [secure-cookie requirement](/calit/installation/reverse-proxy/overview/#why-the-proxy-configuration-matters--secure-login-cookie).

## Create a Proxy Host

In the NPM web UI, go to **Hosts → Proxy Hosts → Add Proxy Host**.

### Details tab

| Field | Value |
|---|---|
| Domain Names | Your public hostname, e.g. `book.example.com` |
| Scheme | `http` |
| Forward Hostname / IP | The hostname or IP of the calit container (e.g. `calit-app` if on the same Docker network, or `127.0.0.1` if on the same host) |
| Forward Port | `8080` |
| Block Common Exploits | Enable |
| Websockets Support | Not required — leave disabled |

### SSL tab

| Field | Value |
|---|---|
| SSL Certificate | Request a new Let's Encrypt certificate |
| Force SSL | Enable |
| HTTP/2 Support | Enable |

Save the host. NPM will obtain the certificate and activate the proxy.

## Set APP_BASE_URL

In calit's `.env`, set:

```
APP_BASE_URL=https://book.example.com
```

## Verification

Browse to `https://book.example.com`. Log in and confirm the session cookie carries the `Secure` attribute (browser DevTools → Application → Cookies). NPM's automatic header forwarding ensures `X-Forwarded-Proto: https` reaches calit, which sets the flag correctly.
