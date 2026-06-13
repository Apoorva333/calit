# calit Documentation Site — Design

**Date:** 2026-06-13
**Branch:** `docs-site`
**Status:** Approved design, ready for implementation planning

## Goal

Build a public documentation site for calit, hosted on GitHub Pages, fully decoupled
from the Quarkus application. The site reuses the existing marketing landing page
(currently the Qute template at `src/main/resources/templates/PublicResource/index.html`)
as its homepage, and adds full documentation: installation, configuration, usage, and
release notes.

## Decisions (locked)

| Topic | Decision |
|-------|----------|
| Hosting | Static site, GitHub Pages, decoupled from the app |
| Generator | Astro Starlight |
| URL | `https://asm0dey.github.io/calit/` (project-page subpath) |
| Astro `base` | `/calit/` |
| Branch | `docs-site` (the name `docs` is blocked by the existing `docs/compose-example` ref) |
| Deploy trigger | GitHub Actions, auto-deploy on push to `docs-site` (path-filtered to `docs-site/**`) |
| Pages source | "GitHub Actions" (set once in repo settings) |
| Search | Starlight built-in Pagefind (zero config) |
| Changelog | Hand-maintained markdown, seeded from existing release commits |
| Screenshots | Captured now from the running app via Playwright |
| Toolchain | Bun (matches the repo's existing CSS toolchain); no Python added |

## Repository layout

The Astro project is self-contained in a `docs-site/` subdirectory so its dependencies
never collide with the root app build or the root `bun.lock`.

```
docs-site/
  astro.config.mjs          # base: '/calit/', site: 'https://asm0dey.github.io'
  package.json              # Bun-managed, independent of root
  bun.lock
  src/
    content/docs/           # markdown doc pages (Starlight content collection)
    styles/landing.css      # lp-* CSS extracted from index.html
    styles/custom.css       # Starlight theme bridge (tokens -> Starlight custom props)
    pages/index.astro       # custom homepage = ported landing
  public/
    img/                    # product-*.png + newly captured usage screenshots
.github/workflows/docs.yml  # build + deploy to Pages
```

The root application is left untouched.

## Component: deploy workflow (`.github/workflows/docs.yml`)

- **Trigger:** `push` to branch `docs-site`, path filter `docs-site/**` (and the workflow
  file itself).
- **Steps:** checkout → setup Bun → `bun install` (in `docs-site/`) → `astro build` →
  upload Pages artifact → `actions/deploy-pages`.
- **Permissions:** `pages: write`, `id-token: write`.
- **Prerequisite (manual, one-time):** repo Settings → Pages → Source = "GitHub Actions".
- **Result:** site served at `https://asm0dey.github.io/calit/`.

## Component: base-path handling

`base: '/calit/'` prefixes all internal URLs and assets.

- Starlight doc pages handle the prefix automatically (links, nav, assets).
- The custom landing (`index.astro`) must reference assets and links through Astro's
  `import.meta.env.BASE_URL` rather than hard-coded absolute paths, otherwise images and
  internal links break under the subpath.

## Component: landing port (`src/pages/index.astro`)

Port the existing ~320-line landing template into an Astro page.

1. **Strip Qute:** remove the `{#include base}` wrapper. Collapse `{#if authenticated}` /
   `{#else}` blocks to the **static logged-out state** — a static site has no auth.
2. **Re-point nav and CTAs:** remove `/me`, `/me/settings`, `/logout`. New nav links:
   **Docs** (into the doc set), **GitHub** (repo), **Features** (in-page anchor).
   Primary CTA → the "Quick start" doc page. Secondary CTA → `#gallery` anchor (kept).
3. **Extract CSS:** move the inline `<style>` block into `src/styles/landing.css` and
   import it in the page. Design tokens (`--indigo #4f46e5`, `--paper`, Fraunces headings,
   Hanken Grotesk body, JetBrains Mono mono) preserved verbatim.
4. **Fonts:** keep the Google Fonts `<link>` for v1 (self-hosting is a possible later
   optimization, out of scope here).
5. **Screenshots:** copy the existing `product-*.png` into `docs-site/public/img/`;
   reference via `BASE_URL`.
6. **Footer:** adjust links/meta for the static context.

The landing uses Starlight's splash/bare layout (no doc sidebar on the homepage).
Doc pages get full Starlight chrome (sidebar, search, prev/next).

## Component: theme bridge (`src/styles/custom.css`)

Map the landing's design tokens onto Starlight's CSS custom properties so doc pages match
the landing's warm-editorial look (Fraunces headings, indigo `#4f46e5` accent, paper
background) instead of Starlight's default blue/grey. Wired via Starlight's
`customCss` config option.

## Component: content / information architecture

Starlight sidebar structure:

```
Introduction
  - What is calit
  - Quick start              (docker compose up -> visit /setup -> create admin)
Installation
  - Docker Compose
  - Configuration            (full env var reference: DB_*, APP_BASE_URL,
                              SESSION_ENCRYPTION_KEY, MAIL_*, SIGNUP_ENABLED, ...)
  - Reverse proxy
      - Overview             (X-Forwarded-* trust, APP_BASE_URL, TLS termination)
      - Nginx Proxy Manager
      - nginx
      - Caddy
      - Traefik
  - Google OAuth setup       (GOOGLE_OAUTH_*, GOOGLE_OAUTH_STATE_SECRET, redirect URI)
  - Cloudflare Turnstile setup
Usage
  - First-run & admin user
  - Meeting types
  - Availability & overrides
  - Bookings & approvals
  - Users & admin            (/me/users)
Releases
  - Changelog                (hand-maintained markdown)
  - Upgrading                (version bumps, DB migration note)
```

### Reverse-proxy pages — required content

Each proxy (Nginx Proxy Manager, nginx, Caddy, Traefik) gets a working, copy-pasteable
config block. The shared overview must cover:

- Trusting `X-Forwarded-*` headers so the login cookie is correctly marked `Secure`
  in production (see commit `cebeb72` — proxy forwarding is required for secure cookies).
- Setting `APP_BASE_URL` to the public HTTPS URL.
- TLS termination at the proxy.
- No websocket configuration needed (the app ships no runtime JS / no WS).

### Cloudflare Turnstile page — required content

- Obtaining a site key + secret key from the Cloudflare dashboard.
- The `TURNSTILE_*` environment variables.
- What it protects: the public booking form (abuse protection).
- Behavior when disabled (default off in dev/test profiles).

### Changelog

Hand-maintained markdown, seeded from existing release commits (e.g. 1.4.0 —
token-at-rest encryption + security remediation; 1.0.1; etc.). Auto-generating from
GitHub Releases is explicitly deferred (possible later automation).

## Component: screenshots

**Existing assets** (in `src/main/resources/META-INF/resources/img/`):
`product-landing.png`, `product-booking.png`, `product-dashboard.png`,
`product-confirmation.png`.

These map to: homepage (already used), Quick start, the public booking flow, and the
Bookings overview.

**New screenshots to capture** (none exist yet) — captured from the running app via
Playwright at a consistent viewport, with seeded demo data:

- Setup / first-run wizard (`/setup`)
- Meeting types list + meeting-type detail editor
- Availability rules editor
- Date overrides
- Booking fields editor
- Users & admin console (`/me/users`)
- Google connect / accounts page

**Capture procedure (for the plan):** start `mvn quarkus:dev` (Docker required for Dev
Services Postgres), ensure seed data exists (admin user is always id 1 per the test
infra), drive the pages with Playwright, save PNGs into `docs-site/public/img/`.

## Out of scope (YAGNI)

- README migration (write fresh docs instead; README stays as-is).
- Doc versioning / multiple versions.
- Custom domain + DNS (project-page subpath now; switchable later via CNAME + base change).
- Self-hosting the web fonts.
- Auto-generating the changelog from GitHub Releases.

## Success criteria

1. Pushing to `docs-site` builds and deploys the site to `https://asm0dey.github.io/calit/`.
2. The homepage visually matches the current landing (warm-editorial style, indigo accent,
   product screenshots), with auth-specific UI removed and nav re-pointed to Docs/GitHub.
3. Doc pages render with Starlight chrome (sidebar, working Pagefind search) and match the
   landing's theme tokens.
4. Installation docs include working reverse-proxy configs for Nginx Proxy Manager, nginx,
   Caddy, and Traefik, plus Google OAuth and Cloudflare Turnstile setup pages.
5. Usage docs carry real screenshots captured from the running app.
6. Changelog + upgrading pages exist.
7. The root application build is unaffected.
