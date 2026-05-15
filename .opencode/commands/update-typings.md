---
description: Fix and add typings from latest concluded "Validate typings" CI run
---

1. Sanity-check that the latest concluded run of the test workflow actually failed. Exit early with a clear message if not:
   ```
   gh run list --workflow .github/workflows/test.yaml --limit 1 --json conclusion
   ```

2. Get the latest run ID, then fetch the "Validate typings" job logs:
   ```
   gh run view <RUN_ID> --log --job validate_typings
   ```

3. Parse the logs - the job might fail because of two reasons (one or both):
  * for already tracked action major versions, the inputs/outputs in the action got changed (e.g. added, removed, renamed). This is shown by `TypingDifference` occurrences in the logs
  * for already tracked actions in general, new major versions may appear. This is shown by `Missing major versions found` in the logs 

  Going forward, prepare separate PRs for each (action, version) pair, so e.g. when fixing typings in a single typing file, make it a separate PR, similarly adding a new major version for a given action.

For a difference in typings:

1. Parse the log for `TypingDifference(...)` lines. Example format:
   ```
   TypingDifference(action=ActionCoords(owner=testlens-app, name=setup-testlens, version=v1, path=null, pathToTypings=typings/testlens-app/setup-testlens/v1/action-types.yml), extraInputsInManifest=[session-timeout-seconds], extraInputsInTypings=[], extraOutputsInManifest=[], extraOutputsInTypings=[])
   ```

2. For each `TypingDifference`:
   - Extract `pathToTypings` (the action-types.yml to edit)
   - Extract `extraInputsInManifest` list (inputs missing from typings but present in the action's manifest)
   - Extract `extraOutputsInManifest` list (outputs missing from typings)
   - Extract `owner`, `name`, `version`, `path` (for fetching the manifest)

   Parse the manifest to determine the type of each missing input/output:
   ```
   gh api repos/{owner}/{name}/contents/action.yml?ref={version}
   ```
   (Try `action.yaml` if `action.yml` doesn't exist. If `path` is not null, prefix with it.)

3. For each missing input, add it to the `inputs:` section of the action-types.yml following the existing format (YAML with `type:` field). The available types are documented in https://github.com/typesafegithub/github-actions-typing/blob/main/README.md. Do not use string by default - there are concrete types available by the typing verification action, so worth looking for the right fit. For each found type, prove it by either linking to the action's docs, action.y(a)ml, or its source code. Do not reorder the attributes - add missing ones to the end.

4. For each missing output, add it similarly under `outputs:`.

5. After all fixes are applied, create a dedicated branch with a commit and push to the branch, then create a PR. Make sure all git and `gh` commands are non-interactive (set `GIT_TERMINAL_PROMPT=0`, configure `user.name`/`user.email` via `-c`). Use `gh pr create` with `--title "Update typings for <action-owner>/<action-name>@<version>"` and `--body` flags. The owner, name, version come from the `ActionCoords` in the `TypingDifference`.

   The PR body should follow this template — an H2 header per action, then list items for each missing input/output with type and reasoning:

   ```markdown
   ## testlens-app/setup-testlens@v1

   - `session-timeout-seconds` (input): proposed type `integer`. The action's action.yml declares it as `type: integer` and it represents a timeout duration in seconds.
   - `api-key` (input): proposed type `string`. The action docs describe it as an API key passed via `inputs.api-key`.
   - `report-url` (output): proposed type `string`. The action's source code calls `core.setOutput("report-url", ...)` with a URL string.
   ```

For a new major version not tracked in this repo:

1. If e.g. v5 is a new major version, start by literally copying the typings from v4, as if there were no changes in typings.

2. Check which inputs/outputs got added or removed in the new major version, and figure out their types.

3. After the typings for the new major version are ready, create a dedicated branch with a commit and push to the branch. In PR description, add explanation for types of new inputs/outputs.
