---
description: Fix typings from latest concluded "Validate typings" CI run
---

1. Get the latest concluded workflow run:
   ```
   gh run list --repo typesafegithub/github-actions-typing-catalog \
     --workflow .github/workflows/test.yaml --limit 1 \
     --json databaseId,conclusion
   ```
    Extract `databaseId` and `conclusion`. If `conclusion` is `success`, exit early (no-op).

2. For the run (which we know is failed), get the "Validate typings" job logs:
   ```
   gh run view <RUN_ID> --repo typesafegithub/github-actions-typing-catalog \
     --log --job validate_typings
   ```

3. Parse the log for `TypingDifference(...)` lines. Example format:
   ```
   TypingDifference(action=ActionCoords(owner=testlens-app, name=setup-testlens, version=v1, path=null, pathToTypings=typings/testlens-app/setup-testlens/v1/action-types.yml), extraInputsInManifest=[session-timeout-seconds], extraInputsInTypings=[], extraOutputsInManifest=[], extraOutputsInTypings=[])
   ```

4. For each `TypingDifference`:
   - Extract `pathToTypings` (the action-types.yml to edit)
   - Extract `extraInputsInManifest` list (inputs missing from typings but present in the action's manifest)
   - Extract `extraOutputsInManifest` list (outputs missing from typings)
   - Extract `owner`, `name`, `version`, `path` (for fetching the manifest)

   Parse the manifest to determine the type of each missing input/output:
   ```
   gh api repos/{owner}/{name}/contents/action.yml?ref={version}
   ```
   (Try `action.yaml` if `action.yml` doesn't exist. If `path` is not null, prefix with it.)

5. For each missing input, add it to the `inputs:` section of the action-types.yml following the existing format (YAML with `type:` field). The available types are documented in https://github.com/typesafegithub/github-actions-typing/blob/main/README.md. Do not use string by default - there are concrete types available by the typing verification action, so worth looking for the right fit. For each found type, prove it by either linking to the action's docs, action.y(a)ml, or its source code. Try to sort alphabetically alongside existing entries.

6. For each missing output, add it similarly under `outputs:`.

7. After all fixes are applied, create a dedicated branch with a commit and push to the branch, then create a PR. Make sure all git and `gh` commands are non-interactive (set `GIT_TERMINAL_PROMPT=0`, configure `user.name`/`user.email` via `-c`, use `gh pr create` with `--title` and `--body` flags).
