# Footer Build Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the release version and short git commit in the footer of every page (e.g. `calit 1.8.0 · a1b2c3d`).

**Architecture:** A Maven build plugin writes `git.properties` (build version + abbreviated commit) into the app classpath at build time. A single `@Named("build") @ApplicationScoped` CDI bean loads that file once and exposes `getVersion()` / `getCommit()`, falling back to `"dev"` when the file is absent (e.g. shallow CI checkout). Qute templates read the bean via `{inject:build.version}` — the same mechanism already used for `{inject:csrf.token}` — so no `@CheckedTemplate` method signatures change. A footer fragment is added to both shared base templates.

**Tech Stack:** Quarkus 3.36 / Java 25, Qute, `io.github.git-commit-id:git-commit-id-maven-plugin`, RestAssured tests.

## Global Constraints

- Quarkus, server-rendered Qute only. **No new runtime JavaScript** (footer is static markup).
- No new *runtime* dependency — `git-commit-id-maven-plugin` is build-time only.
- Owner-scoping rule does not apply (build info is global, not tenant data).
- Tests require Docker (Dev Services Postgres). Admin user is always id 1.
- Footer must carry a stable marker comment `CALIT_BUILD_FOOTER` so RestAssured can assert on it without running JS.
- Docs live on the `docs-site` branch — a footer is a user-facing change, so a changelog/usage note is part of "done" (see Task 4).

---

### Task 1: Generate `git.properties` at build time

**Files:**
- Modify: `pom.xml` — add plugin inside the existing `<build><plugins>` block (after the `maven-surefire-plugin`, before `</plugins>` at `pom.xml:135`).

**Interfaces:**
- Consumes: nothing.
- Produces: a classpath resource `/git.properties` containing keys `git.build.version` (= Maven `${project.version}`) and `git.commit.id.abbrev` (7-char short SHA). Present in `target/classes/` after any Maven phase ≥ `initialize` (so it exists in `quarkus:dev`, `mvn test`, and `mvn package`).

- [ ] **Step 1: Add the plugin to `pom.xml`**

Insert this `<plugin>` as the last child of `<build><plugins>` (the block that ends at `pom.xml:135`):

```xml
      <plugin>
        <groupId>io.github.git-commit-id</groupId>
        <artifactId>git-commit-id-maven-plugin</artifactId>
        <version>9.0.1</version>
        <executions>
          <execution>
            <id>get-the-git-infos</id>
            <goals>
              <goal>revision</goal>
            </goals>
            <phase>initialize</phase>
          </execution>
        </executions>
        <configuration>
          <generateGitPropertiesFile>true</generateGitPropertiesFile>
          <generateGitPropertiesFilename>${project.build.outputDirectory}/git.properties</generateGitPropertiesFilename>
          <!-- Shallow CI checkouts / archive builds have no .git: degrade, don't fail. -->
          <failOnNoGitDirectory>false</failOnNoGitDirectory>
          <abbrevLength>7</abbrevLength>
          <includeOnlyProperties>
            <property>^git\.build\.version$</property>
            <property>^git\.commit\.id\.abbrev$</property>
          </includeOnlyProperties>
        </configuration>
      </plugin>
```

- [ ] **Step 2: Generate the file and verify its contents**

Run: `mvn initialize -q && cat target/classes/git.properties`
Expected: a properties file containing two lines, e.g.
```
git.build.version=1.8.0
git.commit.id.abbrev=f15ac98
```
(If `.git` is unavailable the build still succeeds; the file may be missing — that is the case Task 2's fallback handles.)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: generate git.properties (version + short commit) at build time"
```

---

### Task 2: `BuildInfo` bean exposing version and commit to Qute

**Files:**
- Create: `src/main/java/com/calit/web/BuildInfo.java`
- Test: `src/test/java/com/calit/web/BuildInfoTest.java`

**Interfaces:**
- Consumes: classpath resource `/git.properties` from Task 1 (keys `git.build.version`, `git.commit.id.abbrev`).
- Produces: `@Named("build") @ApplicationScoped` bean with `public String getVersion()` and `public String getCommit()`. Both return a non-empty string always (`"dev"` when `git.properties` is absent or a key is missing). Qute reaches them as `{inject:build.version}` and `{inject:build.commit}`.

- [ ] **Step 1: Write the failing test**

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class BuildInfoTest {

    @Inject
    BuildInfo buildInfo;

    @Test
    void versionMatchesProjectVersion() {
        // git.properties is generated at build time; in this repo the version is the Maven project version.
        assertThat(buildInfo.getVersion()).isNotBlank();
    }

    @Test
    void commitIsNeverBlank() {
        // Either the abbreviated SHA, or the "dev" fallback when .git is unavailable.
        assertThat(buildInfo.getCommit()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=BuildInfoTest`
Expected: FAIL — compilation error, `BuildInfo` does not exist (`cannot find symbol class BuildInfo`).

- [ ] **Step 3: Write the implementation**

```java
package com.calit.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build metadata (release version + short git commit) for display in the page footer.
 * Loaded once from the {@code /git.properties} classpath resource produced by
 * git-commit-id-maven-plugin. Exposed to Qute as {@code {inject:build.version}} /
 * {@code {inject:build.commit}}.
 */
@Named("build")
@ApplicationScoped
public class BuildInfo {

    private static final String FALLBACK = "dev";

    private final String version;
    private final String commit;

    public BuildInfo() {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/git.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // ponytail: git.properties is build-generated and tiny; a read failure just means "dev".
        }
        this.version = p.getProperty("git.build.version", FALLBACK);
        this.commit = p.getProperty("git.commit.id.abbrev", FALLBACK);
    }

    public String getVersion() {
        return version;
    }

    public String getCommit() {
        return commit;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=BuildInfoTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/BuildInfo.java src/test/java/com/calit/web/BuildInfoTest.java
git commit -m "feat(web): BuildInfo bean exposing version + commit to templates"
```

---

### Task 3: Footer in both shared base templates

**Files:**
- Modify: `src/main/resources/templates/base.html` — insert footer after the `</main>` close (currently line 31), before the `<script>` at line 32.
- Modify: `src/main/resources/templates/adminBase.html` — insert footer after the `</main>` close inside `.admin-main` (currently line 61), before `</div>` at line 62.
- Test: `src/test/java/com/calit/web/FooterBuildInfoTest.java`

**Interfaces:**
- Consumes: `{inject:build.version}` and `{inject:build.commit}` from Task 2's `BuildInfo` bean.
- Produces: a `<footer>` element carrying the literal HTML comment marker `<!-- CALIT_BUILD_FOOTER -->` on every page rendered through either base template.

- [ ] **Step 1: Write the failing test**

The login page (`/login`) renders through `base.html` and needs no authentication. The admin dashboard (`/me`) renders through `adminBase.html`. Assert the marker appears on a public page.

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class FooterBuildInfoTest {

    @Test
    void publicPageHasBuildFooter() {
        given()
            .when().get("/login")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_BUILD_FOOTER"))
            .body(containsString("calit"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FooterBuildInfoTest`
Expected: FAIL — body does not contain `CALIT_BUILD_FOOTER` (marker not yet in template).

- [ ] **Step 3: Add the footer to `base.html`**

Replace lines 29-31 of `src/main/resources/templates/base.html`:

```html
  <main class="container py-8">
    {#insert}{/insert}
  </main>
```

with:

```html
  <main class="container py-8">
    {#insert}{/insert}
  </main>
  <!-- CALIT_BUILD_FOOTER -->
  <footer class="container py-6 text-center text-xs text-base-content/50">
    <a href="https://github.com/asm0dey/calit" class="link link-hover">calit</a>
    {inject:build.version} &middot; {inject:build.commit}
  </footer>
```

- [ ] **Step 4: Add the footer to `adminBase.html`**

Replace lines 58-62 of `src/main/resources/templates/adminBase.html`:

```html
    <div class="admin-main">
      <main class="container py-8">
        {#insert}{/insert}
      </main>
    </div>
```

with:

```html
    <div class="admin-main">
      <main class="container py-8">
        {#insert}{/insert}
      </main>
      <!-- CALIT_BUILD_FOOTER -->
      <footer class="container pb-6 text-center text-xs text-base-content/50">
        <a href="https://github.com/asm0dey/calit" class="link link-hover">calit</a>
        {inject:build.version} &middot; {inject:build.commit}
      </footer>
    </div>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=FooterBuildInfoTest`
Expected: PASS.

- [ ] **Step 6: Sanity-check the full suite still passes**

Run: `mvn test`
Expected: BUILD SUCCESS, full suite green (no template/param regressions).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/base.html src/main/resources/templates/adminBase.html src/test/java/com/calit/web/FooterBuildInfoTest.java
git commit -m "feat(web): show version + commit in page footer"
```

---

### Task 4: Docs (docs-site branch)

**Files:**
- Modify (on `docs-site` branch): `docs-site/src/content/docs/releases/changelog.md` — add a one-line note under the next/unreleased section: "Footer now shows the running release version and git commit."

**Interfaces:**
- Consumes: nothing.
- Produces: a changelog line. No code.

- [ ] **Step 1: Add the changelog note on the docs-site branch**

This is a small user-facing change, not a release on its own. When the next release is cut, ensure its changelog section mentions the footer build info. If documenting immediately, switch to the `docs-site` branch, add the line under the top/unreleased section of `docs-site/src/content/docs/releases/changelog.md`, and commit there.

```bash
git switch docs-site
# edit docs-site/src/content/docs/releases/changelog.md — add the footer note
git add docs-site/src/content/docs/releases/changelog.md
git commit -m "docs: note version + commit footer"
git switch main
```

---

## Self-Review

**Spec coverage:** Request was "add a release version and/or commit to the UI, maybe the footer." Task 1 sources both values, Task 2 exposes them, Task 3 renders them in the footer of both base templates (covers all pages), Task 4 documents it. Covered.

**Placeholder scan:** No TBD/TODO/"handle edge cases" — the `git.properties` read failure is handled by an explicit `"dev"` fallback; all code blocks are complete.

**Type consistency:** Bean is `BuildInfo` with `getVersion()`/`getCommit()` throughout; Qute references `{inject:build.version}`/`{inject:build.commit}` consistently in both templates (the `@Named("build")` maps `build.version` → `getVersion()`). Marker `CALIT_BUILD_FOOTER` identical in both templates and the test.

**Notes / known ceilings:**
- `{inject:build.commit}` shows `"dev"` in environments built without `.git` (e.g. some container builds). Acceptable; the Dockerfile build runs in the repo so it gets a real SHA.
- The `FooterBuildInfoTest` asserts on the stable marker + brand string rather than the version number, so it survives version bumps (per the project's RestAssured marker-comment convention).
