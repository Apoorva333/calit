# Google Verification + Legal Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a hosted calit instance pass Google OAuth verification by serving a `google-site-verification` meta tag and public `/privacy` + `/terms` pages — all per-instance configurable, since each deployment is its own data controller on its own domain.

**Architecture:** One `@Named("site") @ApplicationScoped` CDI bean (`SiteInfo`) reads three new env-backed config properties and exposes them to Qute as `{inject:site.*}` — the same pattern as the existing `{inject:build.*}` (`BuildInfo`) and `{inject:csrf.token}`, so **no `@CheckedTemplate` signatures change**. The meta tag is added to both shared `<head>`s (`base.html`, `adminBase.html`) guarded by `{#if inject:site.googleVerification}`. A new `LegalResource` serves `/privacy` and `/terms` from two Qute templates that `{#include base}` and read operator name / contact / origin from `{inject:site.*}`. Footer in `base.html` gains links to both pages.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute, MicroProfile Config (`@ConfigProperty`), RestAssured `@QuarkusTest`.

## Global Constraints

- Server-rendered Qute only. **No new runtime JavaScript.** Legal pages are static markup.
- **No new dependency.** Uses existing MicroProfile Config + Qute CDI injection.
- Owner-scoping rule does **not** apply — site metadata and legal pages are global, not tenant data. `/privacy` and `/terms` are public (no auth, no `@RolesAllowed`).
- `/privacy` and `/terms` are **GET-only, no state mutation** → no CSRF token needed (mirror `LangResource`'s rationale).
- Config defaults must leave the feature **off / safe when unset**: no env vars set → no meta tag, and the policy still renders (operator name falls back to `app.base-url`).
- Tests require Docker (Dev Services Postgres). Admin user is always id 1.
- Legal pages carry stable marker comments `CALIT_LEGAL_PRIVACY` / `CALIT_LEGAL_TERMS` so RestAssured asserts presence without running JS.
- Legal copy is **English-only** (a full bilingual legal translation is out of scope and operators customize anyway). Mark with a `ponytail:` comment; add `msg:` keys only if translation is later requested.
- The privacy page MUST include Google's required **Limited Use** disclosure sentence (Google checks for it during verification).
- Docs live on the `docs-site` branch — new env vars + routes are user-facing, so a docs/changelog update is part of "done" (Task 4 flags the main-branch `.env.example`/`README.md` edits; the `docs-site` branch edit is called out as required follow-up).

---

### Task 1: `SiteInfo` config bean + properties

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/SiteInfo.java`
- Modify: `src/main/resources/application.properties` (add 3 properties near `app.base-url` at line 60)
- Test: `src/test/java/site/asm0dey/calit/web/SiteInfoTest.java`

**Interfaces:**
- Consumes: existing config property `app.base-url`.
- Produces: CDI bean `@Named("site")` with getters `String getGoogleVerification()` (null when blank), `String getOperatorName()` (falls back to base-url when blank), `String getContactEmail()` (null when blank), `String getBaseUrl()`. Reachable in Qute as `{inject:site.googleVerification}`, `{inject:site.operatorName}`, `{inject:site.contactEmail}`, `{inject:site.baseUrl}`.

- [ ] **Step 1: Add the three properties to `application.properties`**

Insert directly after line 60 (`app.base-url=${APP_BASE_URL:http://localhost:8080}`):

```properties
# Optional Google Search Console domain-verification token. When set, every page <head>
# renders <meta name="google-site-verification" content="..."> . The token differs per
# domain, so it cannot be hardcoded. Unset -> no tag (verify via DNS TXT instead).
app.google-site-verification=${GOOGLE_SITE_VERIFICATION:}
# Legal entity running this instance, shown as the data controller on /privacy and /terms.
# Unset -> the pages fall back to APP_BASE_URL.
app.operator-name=${OPERATOR_NAME:}
# Contact address for privacy / data requests, shown on /privacy. Unset -> contact line hidden.
app.privacy-contact=${PRIVACY_CONTACT_EMAIL:}
```

- [ ] **Step 2: Write the failing test**

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SiteInfoTest {

    @Inject
    SiteInfo site;

    @Test
    void unsetOptionalsAreNullAndOperatorFallsBackToBaseUrl() {
        // No GOOGLE_SITE_VERIFICATION / OPERATOR_NAME / PRIVACY_CONTACT_EMAIL in %test.
        assertNull(site.getGoogleVerification());
        assertNull(site.getContactEmail());
        assertEquals(site.getBaseUrl(), site.getOperatorName());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=SiteInfoTest`
Expected: FAIL — compilation error, `SiteInfo` does not exist.

- [ ] **Step 4: Create `SiteInfo.java`**

```java
package site.asm0dey.calit.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Per-instance public site metadata exposed to Qute as {@code {inject:site.*}}.
 *
 * <p>calit is self-hosted and multi-instance: every deployment is its own data controller on its
 * own domain. None of this can be hardcoded, so the operator supplies it via env:
 * {@code GOOGLE_SITE_VERIFICATION} (Google Search Console domain-verification token — differs per
 * domain), plus {@code OPERATOR_NAME} / {@code PRIVACY_CONTACT_EMAIL} which {@code /privacy} and
 * {@code /terms} render so each operator names itself as the controller.</p>
 */
@Named("site")
@ApplicationScoped
public class SiteInfo {

    @ConfigProperty(name = "app.google-site-verification", defaultValue = "")
    String googleSiteVerification;

    @ConfigProperty(name = "app.operator-name", defaultValue = "")
    String operatorName;

    @ConfigProperty(name = "app.privacy-contact", defaultValue = "")
    String privacyContact;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    /** Token for {@code <meta name="google-site-verification">}, or null when unset so {@code {#if}} hides the tag. */
    public String getGoogleVerification() {
        return blankToNull(googleSiteVerification);
    }

    /** Legal entity running this instance; falls back to the public origin so the policy is never blank. */
    public String getOperatorName() {
        String n = blankToNull(operatorName);
        return n != null ? n : baseUrl;
    }

    /** Contact for data/privacy requests, or null when the operator left it unset. */
    public String getContactEmail() {
        return blankToNull(privacyContact);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=SiteInfoTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/SiteInfo.java src/main/resources/application.properties src/test/java/site/asm0dey/calit/web/SiteInfoTest.java
git commit -m "feat(web): per-instance SiteInfo config bean for Google verification + legal pages"
```

---

### Task 2: `google-site-verification` meta tag in both `<head>`s

**Files:**
- Modify: `src/main/resources/templates/base.html` (after line 18, before `{#insert head}` at line 19)
- Modify: `src/main/resources/templates/adminBase.html` (in `<head>`, after the theme `<script>` block, before `</head>`)
- Test: `src/test/java/site/asm0dey/calit/web/GoogleVerificationMetaTest.java`

**Interfaces:**
- Consumes: `{inject:site.googleVerification}` from Task 1.
- Produces: a `<meta name="google-site-verification" content="...">` in every page head when the token is configured; nothing when unset.

- [ ] **Step 1: Write the failing test (token configured via a `@TestProfile`)**

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(GoogleVerificationMetaTest.WithToken.class)
class GoogleVerificationMetaTest {

    public static class WithToken implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.google-site-verification", "tok_calit_test_123");
        }
    }

    @Test
    void publicPageRendersVerificationMetaWhenConfigured() {
        given()
            .when().get("/login")
            .then()
            .statusCode(200)
            .body(containsString("name=\"google-site-verification\""))
            .body(containsString("tok_calit_test_123"));
    }
}
```

Also add to the default-profile test from Task 1's class file a negative assertion — append this method to `SiteInfoTest` is wrong (that's an injected-bean test). Instead add the absence check here is impossible (same class has the token). So create the absence assertion in a plain default-profile test method inside `GoogleVerificationMetaTest`? No — the profile sets the token for the whole class. Put the absence check in a separate default-profile class:

Create `src/test/java/site/asm0dey/calit/web/GoogleVerificationAbsentTest.java`:

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class GoogleVerificationAbsentTest {

    @Test
    void noVerificationMetaWhenUnset() {
        given()
            .when().get("/login")
            .then()
            .statusCode(200)
            .body(not(containsString("google-site-verification")));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=GoogleVerificationMetaTest,GoogleVerificationAbsentTest`
Expected: `GoogleVerificationMetaTest` FAILS (no meta tag yet). `GoogleVerificationAbsentTest` PASSES (nothing renders it yet — that's fine, it stays green after the change too).

- [ ] **Step 3: Add the meta tag to `base.html`**

In `src/main/resources/templates/base.html`, insert between line 18 (`</script>`) and line 19 (`{#insert head}{/insert}`):

```html
  {#if inject:site.googleVerification}
  <meta name="google-site-verification" content="{inject:site.googleVerification}">
  {/if}
```

- [ ] **Step 4: Add the same to `adminBase.html`**

In `src/main/resources/templates/adminBase.html`, insert immediately before `</head>` (after the theme `<script>` block):

```html
  {#if inject:site.googleVerification}
  <meta name="google-site-verification" content="{inject:site.googleVerification}">
  {/if}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=GoogleVerificationMetaTest,GoogleVerificationAbsentTest`
Expected: both PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/base.html src/main/resources/templates/adminBase.html src/test/java/site/asm0dey/calit/web/GoogleVerificationMetaTest.java src/test/java/site/asm0dey/calit/web/GoogleVerificationAbsentTest.java
git commit -m "feat(web): render google-site-verification meta tag when configured"
```

---

### Task 3: `/privacy` + `/terms` pages and footer links

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/LegalResource.java`
- Create: `src/main/resources/templates/LegalResource/privacy.html`
- Create: `src/main/resources/templates/LegalResource/terms.html`
- Modify: `src/main/resources/templates/base.html` (footer, after line 35 `{inject:build.version} ...`)
- Test: `src/test/java/site/asm0dey/calit/web/LegalPagesTest.java`

**Interfaces:**
- Consumes: `base.html` template (via `{#include base}`), `{inject:site.operatorName}`, `{inject:site.contactEmail}`, `{inject:site.baseUrl}` from Task 1.
- Produces: public routes `GET /privacy` and `GET /terms` (HTML, 200). Templates `LegalResource.Templates.privacy(String title)` and `LegalResource.Templates.terms(String title)`.

- [ ] **Step 1: Write the failing test**

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LegalPagesTest {

    @Test
    void privacyPageRendersWithGoogleDisclosure() {
        given()
            .when().get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_LEGAL_PRIVACY"))
            .body(containsString("Google Calendar"))
            .body(containsString("Limited Use"));
    }

    @Test
    void termsPageRenders() {
        given()
            .when().get("/terms")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_LEGAL_TERMS"));
    }

    @Test
    void publicFooterLinksToLegalPages() {
        given()
            .when().get("/login")
            .then()
            .statusCode(200)
            .body(containsString("href=\"/privacy\""))
            .body(containsString("href=\"/terms\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LegalPagesTest`
Expected: FAIL — `/privacy` and `/terms` return 404; footer links absent.

- [ ] **Step 3: Create `LegalResource.java`**

```java
package site.asm0dey.calit.web;

import site.asm0dey.calit.i18n.ActiveLocale;
import site.asm0dey.calit.i18n.AppMessageResolver;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Public privacy policy and terms pages. Required for Google OAuth verification: the consent
 * screen must link a same-domain privacy policy that discloses how Google user data is used.
 * Content is operator-customizable via {@code {inject:site.*}} (see {@link SiteInfo}); GET-only,
 * no state mutation, so no CSRF token (mirrors {@code LangResource}).
 */
@Path("/")
public class LegalResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance privacy(String title);
        public static native TemplateInstance terms(String title);
    }

    @Inject
    AppMessageResolver messages;

    @Inject
    ActiveLocale activeLocale;

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy() {
        return Templates.privacy("Privacy Policy");
    }

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms() {
        return Templates.terms("Terms of Service");
    }
}
```

Note: `messages` / `activeLocale` are injected for parity with other resources and in case future copy needs localization; if the executor's linter rejects unused fields, drop them — the templates use `{inject:site.*}` and `{msg:...}` globals directly. `ponytail:` keep the resource minimal.

- [ ] **Step 4: Create `privacy.html`**

`src/main/resources/templates/LegalResource/privacy.html`:

```html
{@java.lang.String title}
{#include base title=title}
{! ponytail: English-only legal copy. Add msg: keys only if bilingual policy is requested. !}
<article class="prose max-w-2xl mx-auto bg-base-100 border border-base-300 rounded-box p-6">
  <!-- CALIT_LEGAL_PRIVACY -->
  <h1 class="text-2xl font-bold">Privacy Policy</h1>
  <p class="text-base-content/70">This calit instance is operated by <strong>{inject:site.operatorName}</strong> at {inject:site.baseUrl}.</p>

  <h2 class="text-xl font-semibold mt-4">Data we store</h2>
  <ul class="list-disc pl-6">
    <li><strong>Account data</strong> — your name, email, and a hashed (argon2id) password.</li>
    <li><strong>Booking data</strong> — meeting types, availability, and, for each booking, the invitee's name, email, and any answers to questions you configure.</li>
    <li><strong>Google account data</strong> — if you connect Google Calendar, OAuth access/refresh tokens and the calendar identifiers you select.</li>
  </ul>

  <h2 class="text-xl font-semibold mt-4">How we use Google Calendar data</h2>
  <p>When you connect a Google account, calit reads your calendars' busy/free information to avoid offering already-booked times, and creates, updates, or deletes calendar events that correspond to your calit bookings. That is the only use. Google Calendar data is never sold, never used for advertising, and never read by a human except as needed to support you or where required by law.</p>

  <h2 class="text-xl font-semibold mt-4">Limited Use disclosure</h2>
  <p>calit's use and transfer of information received from Google APIs adheres to the <a class="link" href="https://developers.google.com/terms/api-services-user-data-policy" rel="noopener" target="_blank">Google API Services User Data Policy</a>, including the Limited Use requirements.</p>

  <h2 class="text-xl font-semibold mt-4">Storage and retention</h2>
  <p>All data is stored in this instance's PostgreSQL database on infrastructure controlled by the operator. Google tokens are kept until you disconnect the account (at <a class="link" href="/me/google">/me/google</a>) or delete your calit account; disconnecting revokes calit's access and removes the stored tokens.</p>

  <h2 class="text-xl font-semibold mt-4">Your choices</h2>
  <p>You can disconnect Google at any time at <a class="link" href="/me/google">/me/google</a>. To export or delete your account data, contact the operator.</p>

  {#if inject:site.contactEmail}
  <h2 class="text-xl font-semibold mt-4">Contact</h2>
  <p>Privacy questions and data requests: <a class="link" href="mailto:{inject:site.contactEmail}">{inject:site.contactEmail}</a></p>
  {/if}
</article>
{/include}
```

- [ ] **Step 5: Create `terms.html`**

`src/main/resources/templates/LegalResource/terms.html`:

```html
{@java.lang.String title}
{#include base title=title}
{! ponytail: English-only legal copy. Add msg: keys only if bilingual terms are requested. !}
<article class="prose max-w-2xl mx-auto bg-base-100 border border-base-300 rounded-box p-6">
  <!-- CALIT_LEGAL_TERMS -->
  <h1 class="text-2xl font-bold">Terms of Service</h1>
  <p class="text-base-content/70">This calit instance is operated by <strong>{inject:site.operatorName}</strong> at {inject:site.baseUrl}.</p>

  <h2 class="text-xl font-semibold mt-4">The service</h2>
  <p>calit is a scheduling service provided by the operator. By creating an account or booking a meeting you agree to these terms.</p>

  <h2 class="text-xl font-semibold mt-4">Acceptable use</h2>
  <p>Do not use this service to send spam, harass others, or violate any applicable law. The operator may suspend accounts that do.</p>

  <h2 class="text-xl font-semibold mt-4">No warranty</h2>
  <p>The service is provided "as is", without warranty of any kind. The operator is not liable for missed meetings, lost data, or any indirect damages arising from use of the service.</p>

  <h2 class="text-xl font-semibold mt-4">Changes</h2>
  <p>The operator may update these terms; continued use after a change constitutes acceptance.</p>

  {#if inject:site.contactEmail}
  <h2 class="text-xl font-semibold mt-4">Contact</h2>
  <p><a class="link" href="mailto:{inject:site.contactEmail}">{inject:site.contactEmail}</a></p>
  {/if}
</article>
{/include}
```

- [ ] **Step 6: Add footer links in `base.html`**

In `src/main/resources/templates/base.html`, the footer block (lines 33-45) has the `calit` link + build info on one line then a `<nav>` of locales. Insert a legal-links line between the build-info line (line 35) and the locale `<nav>` (line 36). Change:

```html
    {inject:build.version} &middot; {inject:build.commit}
    <nav class="mt-2" aria-label="{msg:common_language}">
```

to:

```html
    {inject:build.version} &middot; {inject:build.commit}
    <div class="mt-1">
      <a href="/privacy" class="link link-hover">Privacy</a>
      <span class="mx-1">&middot;</span>
      <a href="/terms" class="link link-hover">Terms</a>
    </div>
    <nav class="mt-2" aria-label="{msg:common_language}">
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn test -Dtest=LegalPagesTest`
Expected: all three PASS.

- [ ] **Step 8: Check the `/privacy` and `/terms` routes are not shadowed by the public username catch-all**

`PublicResource` serves `/{username}`. Confirm `/privacy` and `/terms` resolve to `LegalResource`, not the username route. JAX-RS prefers literal path segments over `@PathParam` templates, so `/privacy` wins — but verify usernames `privacy`/`terms` cannot be registered. Run:

```bash
grep -rn "RESERVED\|reserved\|isReserved" src/main/java/site/asm0dey/calit/domain/Usernames.java src/main/java/site/asm0dey/calit/user/ 2>/dev/null
```

Expected: a reserved-username list exists. If `privacy`/`terms` are not in it, add them to that list (and its test) so no user can claim a username that collides with a legal route. If no reserved-list mechanism exists, note it and add a one-line guard; do not silently skip.

- [ ] **Step 9: Run the full web suite to confirm no route regression**

Run: `mvn test -Dtest=ReservedRouteTest,PublicUserRoutingTest,LegalPagesTest`
Expected: all PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/LegalResource.java src/main/resources/templates/LegalResource/ src/main/resources/templates/base.html src/test/java/site/asm0dey/calit/web/LegalPagesTest.java
git commit -m "feat(web): public /privacy and /terms pages with footer links"
```

---

### Task 4: Docs — `.env.example`, `README.md`, and docs-site flag

**Files:**
- Modify: `.env.example` (after the Google block, ~line 58)
- Modify: `README.md` (env-var reference table, near line 234 `SIGNUP_ENABLED`; routes table near line 74)

**Interfaces:**
- Consumes: the three property names from Task 1 and the two routes from Task 3.
- Produces: documentation only. No code.

- [ ] **Step 1: Add the new vars to `.env.example`**

Append after the existing Google section (after line 58 `GOOGLE_PROBE_INTERVAL=1h`):

```bash
# --- Public site / Google verification (optional) -----------------------------
# Google Search Console domain-verification token. When set, every page renders
# <meta name="google-site-verification">. Leave blank to verify via DNS TXT instead.
GOOGLE_SITE_VERIFICATION=
# Shown as the data controller on /privacy and /terms. Defaults to APP_BASE_URL if blank.
OPERATOR_NAME=
# Contact address shown on /privacy for data/privacy requests. Hidden if blank.
PRIVACY_CONTACT_EMAIL=
```

- [ ] **Step 2: Add the routes to the README routes table**

In `README.md`, in the routes table (near line 74, after the `/signup` row), add:

```markdown
| `/privacy`, `/terms` | Public privacy policy and terms of service (operator-customizable; required for Google OAuth verification). |
```

- [ ] **Step 3: Add the env vars to the README config reference**

In `README.md`, in the env-var reference table (near line 234), add:

```markdown
| `GOOGLE_SITE_VERIFICATION` | _(empty)_ | Google Search Console domain-verification token. When set, every page renders `<meta name="google-site-verification">`. Leave empty to verify via DNS TXT instead. |
| `OPERATOR_NAME` | `APP_BASE_URL` | Legal entity shown as the data controller on `/privacy` and `/terms`. |
| `PRIVACY_CONTACT_EMAIL` | _(empty)_ | Contact address shown on `/privacy` for privacy/data requests. Hidden when unset. |
```

- [ ] **Step 4: Add a note in the README Google setup section about verification**

In the Google OAuth setup prose (near lines 56-58), add a sentence:

```markdown
For Google to remove the "unverified app" warning and lift the 100-user cap, complete OAuth verification in Google Cloud Console: set `OPERATOR_NAME` and `PRIVACY_CONTACT_EMAIL`, link `${APP_BASE_URL}/privacy` as the consent-screen privacy policy, and verify domain ownership (via `GOOGLE_SITE_VERIFICATION` or a DNS TXT record). Calendar scopes are *sensitive* (not *restricted*), so no third-party security assessment is required.
```

- [ ] **Step 5: Commit**

```bash
git add .env.example README.md
git commit -m "docs: document Google verification + legal-page config"
```

- [ ] **Step 6: Flag required docs-site follow-up**

The `docs-site` branch (Astro Starlight) must get matching updates as part of "done":
- A new section / paragraph in the **configuration** doc covering `GOOGLE_SITE_VERIFICATION`, `OPERATOR_NAME`, `PRIVACY_CONTACT_EMAIL`.
- A note in the **Google setup** doc about completing OAuth verification (privacy policy URL, domain verification, sensitive-vs-restricted scopes).
- If a release is cut for this, a changelog entry in `docs-site/src/content/docs/releases/changelog.md` and a README image-tag bump.

This step is a checklist item, not a code change on `main` — switch to the `docs-site` branch to do it.

---

## Self-Review

- **Spec coverage:** privacy policy ✓ (Task 3), terms ✓ (Task 3), Google site-verification meta tag ✓ (Task 2), per-instance config (multi-tenant data-controller correctness) ✓ (Task 1), Limited Use disclosure ✓ (Task 3 privacy.html + test), docs ✓ (Task 4).
- **Placeholder scan:** all template HTML and Java is concrete; no TBD/TODO.
- **Type consistency:** `SiteInfo` getters (`getGoogleVerification`/`getOperatorName`/`getContactEmail`/`getBaseUrl`) referenced consistently as `{inject:site.googleVerification|operatorName|contactEmail|baseUrl}` in Tasks 2 & 3. `LegalResource.Templates.privacy(String)`/`terms(String)` match their templates' `{@java.lang.String title}`.
- **Open risk handled:** route shadowing by `/{username}` is explicitly checked in Task 3 Step 8 (reserve `privacy`/`terms`).
```
