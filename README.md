# calit — documentation site

This is an **orphan branch** with no shared history with `main`. It holds only the public
documentation site and its deploy pipeline — no application code.

- **Live site:** https://asm0dey.github.io/calit/
- **Source:** `docs-site/` (Astro [Starlight](https://starlight.astro.build/), built with Bun)
- **Deploy:** `.github/workflows/docs.yml` builds `docs-site/` and publishes to GitHub Pages on every push to this branch.

> ⚠️ Do **not** merge `main` into `docs-site` or vice-versa — the histories are intentionally
> unrelated, so a merge would require `--allow-unrelated-histories` and is almost never what you want.
> Application source lives on the `main` branch.

## Local development

```bash
cd docs-site
bun install
bun run dev      # http://localhost:4321/calit/
bun run build    # production build into docs-site/dist
```

Design notes for this site live under `docs/superpowers/`.
