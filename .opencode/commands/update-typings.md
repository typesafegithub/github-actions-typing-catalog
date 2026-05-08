---
description: Fix typings from latest failed "Validate typings" CI run
---

1. Get latest run IDs:
   ```
   gh run list --repo typesafegithub/github-actions-typing-catalog \
     --workflow .github/workflows/test.yaml --limit 1 \
     --json databaseId,conclusion --jq '.[] | select(.conclusion == "failure") | .databaseId'
   ```

2. If no failed run, stop.

3. For the first failed run, get the "Validate typings" job logs:
   ```
   gh run view <RUN_ID> --repo typesafegithub/github-actions-typing-catalog \
     --log --job validate_typings
   ```

4. Parse the log for `TypingDifference(...)` lines. Example format:
   ```
   TypingDifference(action=ActionCoords(owner=testlens-app, name=setup-testlens, version=v1, path=null, pathToTypings=typings/testlens-app/setup-testlens/v1/action-types.yml), extraInputsInManifest=[session-timeout-seconds], extraInputsInTypings=[], extraOutputsInManifest=[], extraOutputsInTypings=[])
   ```

5. For each `TypingDifference`:
   - Extract `pathToTypings` (the action-types.yml to edit)
   - Extract `extraInputsInManifest` list (inputs missing from typings but present in the action's manifest)
   - Extract `extraOutputsInManifest` list (outputs missing from typings)
   - Extract `owner`, `name`, `version`, `path` (for fetching the manifest)

   Parse the manifest to determine the type of each missing input/output:
   ```
   gh api repos/{owner}/{name}/contents/action.yml?ref={version}
   ```
   (Try `action.yaml` if `action.yml` doesn't exist. If `path` is not null, prefix with it.)

6. For each missing input, add it to the `inputs:` section of the action-types.yml following the existing format (YAML with `type:` field). The available types are documented in https://github.com/typesafegithub/github-actions-typing/blob/main/README.md. Do not use string by default - there are concrete types available by the typing verification action, so worth looking for the right fit. Try to sort alphabetically alongside existing entries.

7. For each missing output, add it similarly under `outputs:`.

8. After all fixes are applied, present the user with proposed diff and wait for confirmation.

9. Upon getting the confirmation, create a dedicated branch with a commit and push to the branch, then create a PR.
