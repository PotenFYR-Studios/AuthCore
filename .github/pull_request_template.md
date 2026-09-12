<!-- Thanks for contributing to AuthCore! Please review CONTRIBUTING.md before submitting. -->

## Summary

<!-- One or two sentences: what does this PR do? -->

## Motivation & context

<!-- Why is this change needed? Link related issues ("Fixes #123") or discussions. -->

## Changes

<!-- Bullet list of the notable changes. -->

-

## Variant coverage

<!-- Which build groups / variants does this touch, and which did you verify locally? -->

- [ ] G1 (1.16 - 1.18, Fabric/Forge)
- [ ] G2 (1.19 - 1.21, Fabric/NeoForge)
- [ ] G3 (26.1 - 26.2, Fabric/NeoForge)
- [ ] Proxy roles (BungeeCord / Velocity) unaffected

## Testing done

<!-- Commands you ran and their result, e.g. -->

```text
./gradlew buildAll          # result
./gradlew testAll           # result
bash test/docker/run-tests.sh --groups ... --smoke   # result (if Docker available)
```

## Docs & config impact

- [ ] No player-facing behavior or config change (skip this section)
- [ ] `docs/app/content/*.html` updated for behavior/config changes
- [ ] Locale keys (`messages-*.conf`) updated if user-facing strings changed
- [ ] `changelogs/changelog.md` entry added under the target version

## Checklist

- [ ] Commits follow a clean, descriptive history; the branch is rebased on `master`
- [ ] Java formatting matches the existing sources (google-java-format conventions)
- [ ] No secrets, tokens, or credentials are included in code, logs, or examples
- [ ] Stonecutter conditionals used correctly (shared logic in `src/main/java`, entrypoints in loader roots)
- [ ] I agree my contribution is licensed under the project license (Apache-2.0 with Commons Clause)
