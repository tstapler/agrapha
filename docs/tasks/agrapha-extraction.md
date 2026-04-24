# Feature Plan: Agrapha Extraction — Standalone Public Repo

Created: 2026-04-22
Spec: `project_plans/agrapha-extraction/`
ADRs: `project_plans/agrapha-extraction/decisions/`

---

## Epic Overview

### User Value

`tools/meeting-notes/` is a complete, working macOS app called Agrapha — local-first meeting transcription built on Whisper.cpp, ScreenCaptureKit, and Compose Desktop. It is invisible to the public because it lives inside a private personal wiki monorepo. After this epic, Agrapha exists as `tstapler/agrapha` on GitHub, installable via `brew install --cask agrapha`, with a tagged v1.0.0 DMG release. A privacy-conscious developer in the Logseq Discord or on HN can discover it, install it, and transcribe a meeting without asking for help.

### Success Criteria

- Tagged `v1.0.0` release with a downloadable DMG attached to the GitHub Release
- `brew tap tstapler/agrapha && brew install --cask agrapha` succeeds and installs a working app
- GitHub Actions CI passes on push to `main`: build + test
- No personal data, private wiki content, credentials, or internal tooling references in the public repo
- Someone who has never seen the codebase can clone, download a Whisper model via the Settings UI, and transcribe a meeting without external help
- README is accurate: install instructions, first-run steps, how-it-works, license

### Architecture Decisions

| ADR | File | Decision |
|---|---|---|
| ADR-001 | `decisions/ADR-001-fresh-repo-vs-git-filter-repo.md` | Fresh repo (rsync + git init) — zero history leak risk |
| ADR-002 | `decisions/ADR-002-elv2-license.md` | Elastic License 2.0 — prevents SaaS wrapping, compatible with all deps |
| ADR-003 | `decisions/ADR-003-personal-homebrew-tap-unsigned.md` | Personal tap, unsigned DMG, notarization deferred post-v1 |
| ADR-004 | `decisions/ADR-004-givimad-whisper-jni-vs-custom-cmake.md` | Evaluate whisper-jni:1.7.1 first; fall back to custom CMake if no CoreML arm64 dylib |

---

## Story Breakdown

### Story 1: Pre-Extraction Audit and Preparation

**User value:** Every file in the public repo has been examined. No private data, credentials, or internal wiki references exist in the tree. All renames and file exclusions are complete before the first git push. The three open questions from research are resolved so no later story is blocked.

**Acceptance criteria:**
- Spike ADR-004: `jar tf whisper-jni-1.7.1.jar | grep dylib` result documented; `libs.versions.toml` version and Makefile tag updated atomically if 1.7.1 is taken
- `native/` directory audited: no LGPL audio libraries statically linked; `diarize_session.py` has no hardcoded paths or credentials
- `docs/tasks/` reviewed: `fluida-audio-backends.md` and `transcription-diarization-improvement.md` do not contain private data and are safe to publish; alternatively they are excluded
- `logos/` SVGs audited for metadata: no `<dc:creator>`, `<dc:rights>`, or author name strings in any SVG file
- `settings.gradle.kts` updated: `rootProject.name = "agrapha"` (was `"meeting-notes"`)
- `composeApp/build.gradle.kts` `mainClass` and any `com.meetingnotes` package references audited (package rename is NOT required for v1 — only the project name and app display name matter)
- Pre-push scrub checklist passes with zero findings (see checklist below)
- Excluded directories enumerated: `.claude/`, `project_plans/`
- `docs/tasks/` inclusion decision made and documented in commit message

---

#### Task 1.1: Resolve Whisper JNI Spike (ADR-004) [BLOCKING — spike pending macOS machine]

**Status**: CI safety flags added to Makefile (`-DGGML_NO_I8MM=ON -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_KOMPUTE=OFF`) — Option B (custom CMake) is in place. Run the Maven spike below on a macOS machine to determine if Option A (Maven 1.7.1 with CoreML) can replace the custom build step.



**Files:** `gradle/libs.versions.toml`, `native/WhisperCoreML/Makefile`

Run the spike validation from ADR-004:

```bash
cd /tmp
mvn dependency:get -Dartifact=io.github.givimad:whisper-jni:1.7.1 -q
jar tf ~/.m2/repository/io/github/givimad/whisper-jni/1.7.1/whisper-jni-1.7.1.jar | grep -E 'dylib|\.so|\.dll'
```

**Path A (Maven 1.7.1 has macOS arm64 dylib with CoreML):**
- Update `libs.versions.toml`: `whisper-jni = "1.7.1"`
- Update `native/WhisperCoreML/Makefile`: `WHISPER_JNI_TAG ?= v1.7.1`
- Note CI workflow will NOT need a custom CMake build step

**Path B (Maven 1.7.1 lacks CoreML or arm64 dylib):**
- Keep `libs.versions.toml` at `1.6.1`
- Add `-DGGML_NO_I8MM=ON -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_KOMPUTE=OFF` to the `cmake` invocation in `native/WhisperCoreML/Makefile`
- Note CI workflow WILL need the custom CMake build step

Document the finding in a comment at the top of `native/WhisperCoreML/Makefile`.

---

#### Task 1.2: Native Directory Audit [BLOCKING]

**Files:** `native/diarize_session.py`, `native/AudioCaptureBridge/Package.swift`, `native/WhisperCoreML/Makefile`

```bash
# Check for hardcoded paths in diarize_session.py
grep -n "/Users\|/home\|~/" native/diarize_session.py

# Check for license-contaminating libraries
./gradlew dependencies 2>/dev/null | grep -iE 'gpl|lgpl'

# Check Package.swift for private registry URLs
grep -n "url:" native/AudioCaptureBridge/Package.swift
```

`diarize_session.py` uses only `argparse`, `json`, `os`, `sys`, and `pyannote.audio`. No hardcoded paths are present. Mark as safe.

If any GPL/LGPL dependency is found in `./gradlew dependencies` output, file an issue before proceeding.

---

#### Task 1.3: SVG Logo Metadata Audit

**Files:** `logos/concepts/*.svg`, `logos/iterations/*.svg`, `logos/preview.html`

Open each SVG in a text editor and search for:
- `<dc:creator>`, `<dc:rights>`, `<cc:Agent>`, `rdf:about`
- Author names, email addresses, or tool-generated metadata (Inkscape, Illustrator export headers)
- Any `[[` Logseq link patterns

```bash
grep -rn "dc:creator\|dc:rights\|rdf:about\|@\|tyler\|stapler" logos/
```

Strip any personal metadata found using a text editor before the rsync step. SVG metadata does not affect rendering.

---

#### Task 1.4: docs/tasks/ Inclusion Decision

**Files:** `docs/tasks/fluida-audio-backends.md`, `docs/tasks/transcription-diarization-improvement.md`

Read each file and check for:
- `[[Wiki Links]]` (Logseq syntax indicating private wiki references)
- Employer names, team names, internal project names
- Credentials or tokens

Both files are feature plans for this codebase. They are implementation roadmaps, not personal notes. If they pass the private-data check, include them — they provide useful context for contributors. If they contain private references, exclude them from the rsync.

Document the decision in `docs/tasks/agrapha-extraction.md` (this file) before the rsync.

**Decision (2026-04-23)**: INCLUDE both files. Neither contains `[[Wiki Links]]`, employer names, credentials, or private data. They are feature plans for this codebase and provide useful context for contributors.

---

#### Task 1.5: Project Rename and Package Audit

**Files:** `settings.gradle.kts`, `composeApp/build.gradle.kts`

```bash
# Rename the project
# settings.gradle.kts: rootProject.name = "meeting-notes" → "agrapha"

# Audit mainClass package — com.meetingnotes is the internal package name
# This does NOT need to change for v1 (it only affects .class file paths, not the app display name)
# Document the decision: package rename is post-v1 cleanup, not an extraction blocker
grep -rn "com.meetingnotes" composeApp/src/
```

Apply only the `settings.gradle.kts` rename. Leave `com.meetingnotes` package paths unchanged for v1 — a package rename requires updating every Kotlin source file and is a separate task.

---

#### Task 1.6: Pre-Push Scrub Checklist

**Files:** entire working tree

Run this checklist and fix any finding before proceeding to Story 2:

```bash
# 1. No .env files or local.properties with secrets
find . -name "*.env" -o -name "local.properties" -o -name "*.keystore" | grep -v ".git"

# 2. No Logseq wiki link syntax in any file
find . -name "*.md" -o -name "*.kt" -o -name "*.swift" -o -name "*.py" | \
  xargs grep -l "\[\[" 2>/dev/null | grep -v ".git"

# 3. No credentials, API keys, tokens, secrets in source
git grep -iE "password|api_key|token|secret|private_key" -- \
  ":(exclude).git" ":(exclude)docs/tasks/agrapha-extraction.md" 2>/dev/null

# 4. No GPL/LGPL in dependency graph
./gradlew dependencies 2>/dev/null | grep -iE 'gpl|lgpl'

# 5. No personal paths or usernames
grep -rn "/Users/tylerstapler\|/home/tstapler\|tystapler@\|tyler.stapler@" \
  --include="*.md" --include="*.kt" --include="*.swift" --include="*.py" \
  --include="*.gradle.kts" --include="*.toml" .

# 6. No .claude/ or project_plans/ references in non-excluded files
grep -rn "\.claude\|project_plans" --include="*.md" --include="*.kt" . | \
  grep -v "docs/tasks/agrapha-extraction.md"
```

Zero findings required to proceed. Any finding is a blocker.

---

### Story 2: Repo Setup and CI

**User value:** `tstapler/agrapha` exists on GitHub, the working tree is pushed as the initial commit, and CI runs successfully on push to `main`. Anyone can clone the repo and build it with `./gradlew :composeApp:packageReleaseDmg`.

**Acceptance criteria:**
- `tstapler/agrapha` GitHub repo created: public, no template, no README (README comes from the source tree)
- Initial commit contains exactly the audited working tree from Story 1, minus `.claude/` and `project_plans/`
- `.gitignore` excludes: `local.properties`, `*.keystore`, `*.jks`, `.gradle/`, `build/`, `composeApp/build/`, `native/WhisperCoreML/build/`, `native/AudioCaptureBridge/.build/`
- GitHub Actions workflow `build.yml` runs on push to `main` and on pull requests
- `build.yml` job: check out, set up JDK 17, run `./gradlew :composeApp:desktopTest`
- `build.yml` job: run `./gradlew :composeApp:packageReleaseDmg` (build verification, artifact not uploaded on every push — only on tags)
- CI passes on first push
- README CI badge URL points to `tstapler/agrapha` (not a placeholder)

---

#### Task 2.1: Create GitHub Repo and Push Initial Commit

**Files:** entire audited tree (from Story 1)

```bash
# In a temp directory outside the monorepo
DEST=/tmp/agrapha-public
mkdir -p $DEST

# rsync the audited tree, excluding private directories
rsync -av --exclude='.git' \
          --exclude='.claude/' \
          --exclude='project_plans/' \
          /path/to/tools/meeting-notes/ \
          $DEST/

cd $DEST
git init -b main
git add .
git commit -m "Initial public release — Agrapha v1.0.0"

# Create GitHub repo (requires gh CLI)
gh repo create tstapler/agrapha --public --source=. --push
```

Verify the remote shows only the expected files: no `.claude/`, no `project_plans/`, no journal filenames in commit messages.

---

#### Task 2.2: Audit .gitignore for Public Repo

**Files:** `.gitignore`

The current `.gitignore` was written for the monorepo context. Verify and update it for a standalone repo:

Required entries for the public repo:
```
# Build outputs
build/
.gradle/
composeApp/build/

# Native build artifacts
native/WhisperCoreML/build/
native/AudioCaptureBridge/.build/
composeApp/src/desktopMain/resources/libwhisperjni-coreml.dylib
composeApp/src/desktopMain/resources/AudioCaptureBridgeJNI.dylib
composeApp/src/desktopMain/resources/libAudioCaptureBridge.dylib

# macOS
.DS_Store

# Local config (never commit)
local.properties
*.keystore
*.jks

# IDE
.idea/
*.iml
```

The native dylib resources must be in `.gitignore`: they are build outputs that CI generates, not committed binaries.

---

#### Task 2.3: Write GitHub Actions build.yml

**Files:** `.github/workflows/build.yml` (new file)

```yaml
name: Build and Test

on:
  push:
    branches: [main]
    tags-ignore: ['**']   # release.yml handles tags
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: macos-14
    timeout-minutes: 45

    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      # --- Native dylib build ---
      # Path A: Maven artifact has CoreML dylib — skip this step
      # Path B: Custom CMake build required
      - name: Build WhisperCoreML dylib
        if: <RESULT_OF_TASK_1.1_SPIKE>
        run: |
          brew install cmake
          cd native/WhisperCoreML
          make

      - name: Build AudioCaptureBridge dylib
        run: |
          cd native/AudioCaptureBridge
          make

      # --- Gradle build and test ---
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
            native/WhisperCoreML/build
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml') }}

      - name: Run tests
        run: ./gradlew :composeApp:desktopTest --no-daemon

      - name: Build DMG (verification only)
        run: ./gradlew :composeApp:packageReleaseDmg --no-daemon
```

The `if:` condition on the CMake step is set based on the Task 1.1 spike result. Update the YAML with the correct value before committing.

---

#### Task 2.4: Verify CI Passes on First Push

After Task 2.3 is committed and pushed:
- Watch the Actions tab on `tstapler/agrapha`
- Confirm green on the `Build and Test` workflow
- Fix any build failures before proceeding to Story 3 (Story 3 depends on a working build)

Common first-push failures:
- Missing `Agrapha.icns` in resources (Gradle DMG packaging requires the icon file)
- Missing macOS entitlements file
- JNI dylib not found at runtime during tests (WhisperService loads dylib from resources)

---

### Story 3: Release Pipeline and Homebrew Tap

**User value:** Anyone can run `brew tap tstapler/agrapha && brew install --cask agrapha`. A tagged push to `tstapler/agrapha` produces a DMG on GitHub Releases. The cask formula pins the SHA256 so installs are reproducible.

**Acceptance criteria:**
- `.github/workflows/release.yml` triggers on `v*` tag push
- `release.yml` builds the DMG, attaches it to the GitHub Release, and outputs the SHA256
- GitHub Release for `v1.0.0` has `Agrapha-1.0.0.dmg` as a release asset
- `tstapler/homebrew-agrapha` repo exists with a `Casks/agrapha.rb` formula
- `brew tap tstapler/agrapha && brew install --cask agrapha` installs the app from the v1.0.0 release
- Cask `caveats` block surfaces the `xattr` quarantine removal command

---

#### Task 3.1: Write GitHub Actions release.yml

**Files:** `.github/workflows/release.yml` (new file in `tstapler/agrapha`)

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: macos-14
    timeout-minutes: 60
    permissions:
      contents: write   # required for gh release create

    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Build WhisperCoreML dylib
        if: <RESULT_OF_TASK_1.1_SPIKE>
        run: |
          brew install cmake
          cd native/WhisperCoreML
          make

      - name: Build AudioCaptureBridge dylib
        run: |
          cd native/AudioCaptureBridge
          make

      - name: Build release DMG
        run: ./gradlew :composeApp:packageReleaseDmg --no-daemon

      - name: Locate DMG
        id: dmg
        run: |
          DMG=$(find composeApp/build/compose/binaries/main-release/dmg -name '*.dmg' | head -1)
          echo "path=$DMG" >> $GITHUB_OUTPUT
          echo "name=$(basename $DMG)" >> $GITHUB_OUTPUT
          echo "sha256=$(shasum -a 256 $DMG | awk '{print $1}')" >> $GITHUB_OUTPUT

      - name: Create GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "${{ github.ref_name }}" \
            "${{ steps.dmg.outputs.path }}" \
            --title "Agrapha ${{ github.ref_name }}" \
            --generate-notes

      - name: Print SHA256 for cask formula
        run: |
          echo "DMG SHA256: ${{ steps.dmg.outputs.sha256 }}"
          echo "Update Casks/agrapha.rb with this value."
```

---

#### Task 3.2: Tag v1.0.0 and Capture SHA256

After Task 3.1 is merged to `main` in `tstapler/agrapha`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Watch the `Release` workflow. When it completes:
- Note the SHA256 printed in the "Print SHA256 for cask formula" step
- Download the DMG from GitHub Releases and verify it launches (even with the Gatekeeper warning)
- Record the SHA256 for use in Task 3.3

---

#### Task 3.3: Create tstapler/homebrew-agrapha Repo and Cask Formula

**Files:** `Casks/agrapha.rb` (in new `tstapler/homebrew-agrapha` repo)

Create the tap repository:

```bash
gh repo create tstapler/homebrew-agrapha --public --description "Homebrew tap for Agrapha"
cd /tmp && mkdir homebrew-agrapha && cd homebrew-agrapha
git init -b main
mkdir -p Casks
```

Write `Casks/agrapha.rb`:

```ruby
cask "agrapha" do
  version "1.0.0"
  sha256 "<SHA256-FROM-TASK-3.2>"

  url "https://github.com/tstapler/agrapha/releases/download/v#{version}/Agrapha-#{version}.dmg"
  name "Agrapha"
  desc "Local-first meeting transcription for your memory system"
  homepage "https://github.com/tstapler/agrapha"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "Agrapha.app"

  caveats <<~EOS
    Agrapha is unsigned. macOS Gatekeeper will block it on first launch.
    To allow the app, run:
      xattr -rd com.apple.quarantine /Applications/Agrapha.app
    Or: right-click Agrapha.app in Finder → Open.
  EOS
end
```

Commit, push, and verify:

```bash
brew tap tstapler/agrapha
brew install --cask agrapha
```

---

#### Task 3.4: Verify End-to-End Install Flow

On a clean macOS machine (or a fresh user profile without prior Agrapha install):

1. `brew tap tstapler/agrapha`
2. `brew install --cask agrapha`
3. Open `/Applications/Agrapha.app` — expect Gatekeeper warning
4. Run `xattr -rd com.apple.quarantine /Applications/Agrapha.app`
5. Open Agrapha — verify it launches, settings screen appears
6. Verify the README install instructions are accurate against this flow

If step 2 fails: check SHA256 in the cask formula against the actual DMG.
If step 5 fails: the DMG is missing required resources (icon, entitlements, dylibs) — return to Story 2.

---

### Story 4: Post-Extraction Cleanup

**User value:** The monorepo is updated to reflect that Agrapha is now a standalone project. Internal references are cleaned up. A Logseq `[[Agrapha]]` page provides a navigation anchor in the wiki. Future work on Agrapha is tracked in the public repo, not in the monorepo.

**Acceptance criteria:**
- `tools/meeting-notes/` is removed from the monorepo (or replaced with a stub README pointing to `tstapler/agrapha`)
- Monorepo `CLAUDE.md` no longer references `tools/meeting-notes/` as a local tool
- `logseq/pages/Agrapha.md` Zettelkasten page created with project summary, GitHub link, and relevant tags
- Any monorepo CI or Makefile targets that reference `tools/meeting-notes/` are updated or removed

---

#### Task 4.1: Remove or Stub tools/meeting-notes/ in Monorepo

**Files:** `tools/meeting-notes/` (in monorepo)

Decision: replace the directory with a single stub `README.md` so the directory path still exists (preventing 404s in any bookmarked file references) but clearly directs visitors to the public repo.

Stub content:
```markdown
# Agrapha

This tool has been extracted to a standalone public repository.

https://github.com/tstapler/agrapha

Install: `brew tap tstapler/agrapha && brew install --cask agrapha`
```

Remove all other content from `tools/meeting-notes/` in the monorepo. Keep the directory and the stub README.

---

#### Task 4.2: Update Monorepo CLAUDE.md

**Files:** `CLAUDE.md` (at monorepo root)

Remove the `tools/meeting-notes/` section from the monorepo `CLAUDE.md`. The public repo has its own `CLAUDE.md` (the existing file in the `tools/meeting-notes/` directory is the app's CLAUDE.md — it was already there).

If the monorepo CLAUDE.md references meeting-notes tools, scripts, or commands, remove or replace them with a pointer to the public repo.

---

#### Task 4.3: Create logseq/pages/Agrapha.md

**Files:** `logseq/pages/Agrapha.md` (new file in monorepo)

Create a Zettelkasten page for the Agrapha project:

```markdown
- type:: [[Project]]
- status:: [[Active]]
- repo:: https://github.com/tstapler/agrapha
- license:: [[Elastic License 2.0]]
- tags:: [[Open Source]], [[Meeting Notes]], [[Whisper]], [[Compose Desktop]], [[Local-First]]

## Summary

[[Agrapha]] is a local-first macOS app for meeting transcription. Records both microphone and system audio via [[ScreenCaptureKit]], transcribes on-device using [[Whisper.cpp]] via JNI, and exports structured notes (key points, decisions, action items) to [[Logseq]] or plain markdown.

Built with [[Kotlin Multiplatform]] + [[Compose Desktop]], [[SQLDelight]], [[Ktor]].

## Install

```bash
brew tap tstapler/agrapha
brew install --cask agrapha
```

## See Also

- [[PKM Tools]]
- [[Local-First Software]]
- [[Whisper]]
```

---

## Known Issues

### Whisper.cpp i8mm CI Failure [SEVERITY: High]

**Description:** whisper.cpp GitHub Issue #3427 documents a build failure on macOS GitHub Actions runners when the i8mm ARM instruction set extension is enabled by default in the CMake configuration. This causes CI to fail with a compiler error during the CMake build step in Story 2.

**Affected story:** Task 2.3 (build.yml), Task 1.1 (Makefile update)

**Mitigation:**
- Task 1.1 adds `-DGGML_NO_I8MM=ON -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_KOMPUTE=OFF` to the CMake invocation in `native/WhisperCoreML/Makefile` if Option B is taken
- If Option A (Maven 1.7.1 with CoreML) is taken, the CMake build step is eliminated from CI entirely, removing this risk
- The Makefile fix is the first task in Story 1 and is marked BLOCKING

**Files affected:** `native/WhisperCoreML/Makefile`, `.github/workflows/build.yml`

---

### Gatekeeper Distribution Friction [SEVERITY: High]

**Description:** macOS 15.1+ removed the Control-click bypass for unidentified developers. Users downloading the unsigned DMG must use `xattr -rd com.apple.quarantine /Applications/Agrapha.app` from a terminal. Non-technical users may not know this step and will see a blocking "Apple cannot verify this app for malware" dialog with no obvious bypass path.

**Affected story:** Task 3.4 (end-to-end verification), ADR-003

**Mitigation:**
- Homebrew cask `caveats` block (Task 3.3) automatically surfaces the `xattr` command at install time — users who install via Homebrew see the workaround before they need it
- README `## Install` section includes the `xattr` command prominently above the fold
- The comparison with `xattr` is macOS-developer standard behavior and is acceptable for the v1 target audience

**Post-v1 resolution:** Notarize with Apple Developer Program ($99/year). See ADR-003 for trigger conditions.

**Files affected:** `README.md`, `Casks/agrapha.rb` (in homebrew-agrapha repo)

---

### Native Dylib Missing from Resources at Test Time [SEVERITY: High]

**Description:** `WhisperService` and `AudioCaptureService` call `System.load()` to load JNI dylibs from the classpath resources directory. If the native build step (`make` in `native/WhisperCoreML/` and `native/AudioCaptureBridge/`) has not run before `./gradlew :composeApp:desktopTest`, the JNI load will fail and any test that exercises these services will crash with `UnsatisfiedLinkError`.

**Affected story:** Task 2.3 (build.yml ordering)

**Mitigation:**
- `build.yml` runs the native build steps before the Gradle test step (tasks 2.3)
- Tests that exercise JNI-dependent services must check for dylib availability and skip gracefully on CI when dylibs are not present, OR CI must always run the full native build
- Recommended: wrap JNI load in try-catch in tests; annotate JNI-dependent tests with `@Ignore("requires native dylib")` only as a last resort — prefer CI always building the dylib

**Files affected:** `.github/workflows/build.yml`, any test file that instantiates `WhisperService` or `AudioCaptureService`

---

### SVG Metadata Personal Data Leak [SEVERITY: Medium]

**Description:** Logo SVG files exported from Inkscape, Illustrator, or other vector tools embed `<dc:creator>`, `<dc:rights>`, and `rdf:about` metadata containing author names or email addresses. These are invisible in the rendered logo but present in the raw XML.

**Affected story:** Task 1.3

**Mitigation:**
- Task 1.3 runs `grep -rn "dc:creator\|dc:rights\|rdf:about\|tyler\|stapler" logos/` before the rsync
- Any SVG metadata found is stripped with a text editor (not a render-affecting change)
- The scrub checklist in Task 1.6 catches any remaining instances

**Files affected:** `logos/concepts/*.svg`, `logos/iterations/*.svg`

---

### License Badge and File Mismatch [SEVERITY: Medium]

**Description:** `README.md` currently shows `[![License: MIT]...]` and the "License" section says "MIT — see LICENSE". The requirements specify ELv2. If the LICENSE file is updated but the README badge is not (or vice versa), the repo presents contradictory license information.

**Affected story:** Task 1.5 (or a dedicated pre-push check)

**Mitigation:**
- Story 1 pre-push checklist item: `grep -n "MIT\|Apache\|GPL" README.md LICENSE` — any non-ELv2 reference is a blocker
- Update both README badge and LICENSE file atomically in the same commit
- README "License" section must read: "Elastic License 2.0 — see [LICENSE](LICENSE). Free to use, modify, and distribute; use as a managed service is not permitted."

**Files affected:** `README.md`, `LICENSE`

---

### com.meetingnotes Package in Public Repo [SEVERITY: Low]

**Description:** All Kotlin source files use the `com.meetingnotes` package prefix. This is an internal name referencing the original app name before branding as Agrapha. It is visible to anyone who reads the source code.

**Decision for v1:** Leave unchanged. A package rename requires touching every `.kt` source file — ~50+ files — and is a pure cosmetic change with no functional benefit for v1. The app display name, bundle ID, and project name are already `Agrapha`.

**Post-v1 resolution:** Rename `com.meetingnotes` to `com.agrapha` in a dedicated refactor commit after v1.0.0 is tagged.

**Files affected:** All files under `composeApp/src/` (not a v1 blocker)

---

### Homebrew Tap Formula SHA256 Staleness [SEVERITY: Low]

**Description:** The cask formula in `tstapler/homebrew-agrapha` pins a specific SHA256. Each new release requires manually updating the formula. If the SHA256 is wrong (e.g., DMG was rebuilt without re-tagging), `brew install` fails with a checksum mismatch.

**Mitigation:**
- Task 3.1 `release.yml` prints the SHA256 to the job log — copy it to the formula after each release
- The `livecheck` block in the cask formula (Task 3.3) enables `brew livecheck` to detect version updates, but SHA256 must still be updated manually
- Post-v1: consider a GitHub Actions workflow in `tstapler/homebrew-agrapha` that automatically updates the formula SHA256 when a new release is published to `tstapler/agrapha` using the `repository_dispatch` event

**Files affected:** `Casks/agrapha.rb` (in homebrew-agrapha repo)

---

## Dependency Visualization

```
Story 1: Pre-Extraction Audit and Preparation
  |
  +-- Task 1.1: Whisper JNI Spike [BLOCKING — resolves ADR-004]
  |
  +-- Task 1.2: Native Directory Audit [BLOCKING — GPL check]
  |
  +-- Task 1.3: SVG Logo Metadata Audit
  |
  +-- Task 1.4: docs/tasks/ Inclusion Decision
  |
  +-- Task 1.5: Project Rename (settings.gradle.kts)
  |
  +-- Task 1.6: Pre-Push Scrub Checklist [BLOCKING — must pass before Story 2]
  |
  v
Story 2: Repo Setup and CI
  |
  +-- Task 2.1: Create GitHub Repo + Initial Commit [depends on: Story 1 complete]
  |
  +-- Task 2.2: Audit .gitignore
  |
  +-- Task 2.3: Write build.yml [depends on: ADR-004 spike result from Task 1.1]
  |
  +-- Task 2.4: Verify CI Passes [depends on: 2.1, 2.2, 2.3; BLOCKING for Story 3]
  |
  v
Story 3: Release Pipeline and Homebrew Tap
  |
  +-- Task 3.1: Write release.yml [depends on: 2.4 green CI]
  |
  +-- Task 3.2: Tag v1.0.0 + Capture SHA256 [depends on: 3.1]
  |
  +-- Task 3.3: Create homebrew-agrapha Repo + Cask Formula [depends on: 3.2]
  |
  +-- Task 3.4: Verify End-to-End Install Flow [depends on: 3.3]
  |
  v
Story 4: Post-Extraction Cleanup [depends on: Story 3 complete]
  |
  +-- Task 4.1: Remove/Stub tools/meeting-notes/ in Monorepo
  |
  +-- Task 4.2: Update Monorepo CLAUDE.md
  |
  +-- Task 4.3: Create logseq/pages/Agrapha.md


Parallel tracks within Story 1 (all unblock Task 1.6):
  Task 1.1 ──┐
  Task 1.2 ──┤
  Task 1.3 ──┼──► Task 1.6 (scrub checklist) ──► Story 2
  Task 1.4 ──┤
  Task 1.5 ──┘

Critical path: 1.1 → 1.6 → 2.1 → 2.4 → 3.1 → 3.2 → 3.3 → 3.4
```

---

## Context Preparation Guide Per Story

### Story 1 (Audit)
Load into context before starting:
- `gradle/libs.versions.toml` (whisper-jni version)
- `native/WhisperCoreML/Makefile` (CMake flags to update)
- `native/diarize_session.py` (private path check)
- `README.md` (MIT badge to replace)

### Story 2 (CI)
Load into context before starting:
- `composeApp/build.gradle.kts` (DMG output path: `composeApp/build/compose/binaries/main-release/dmg/`)
- `native/AudioCaptureBridge/Makefile` (build command for CI)
- ADR-004 spike result (determines whether CMake step is in build.yml)

### Story 3 (Release + Homebrew)
Load into context before starting:
- SHA256 from Task 3.2 job log
- ADR-003 for caveats text
- GitHub CLI docs for `gh release create`

### Story 4 (Cleanup)
Load into context before starting:
- Monorepo `CLAUDE.md` (section to remove)
- `logseq/pages/` — review one existing page for Zettelkasten format conventions before writing `Agrapha.md`
