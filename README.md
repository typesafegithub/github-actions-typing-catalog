# GitHub Actions Typing Catalog

This repository contains type definitions for various GitHub Actions APIs (inputs and outputs). It's best for action
owners to provide the typings themselves and host them from within their repositories (see
[typesafegithub/github-actions-typing](https://github.com/typesafegithub/github-actions-typing)), but if it's
impossible, this repo is a fallback solution.

It uses the same format as in [typesafegithub/github-actions-typing](https://github.com/typesafegithub/github-actions-typing)
to describe the typings.

This repo is to GitHub Actions what [DefinitelyTyped](https://github.com/DefinitelyTyped/DefinitelyTyped) is to
TypeScript libraries, [typeshed](https://github.com/python/typeshed) is to Python ecosystem, or
[SchemaStore](https://github.com/SchemaStore/schemastore) is to JSON.

## Typing mismatches

`typing-mismatches.yml` lists all cases where a typing stored in this catalog differs from the type inferred by the
heuristics in `.github/workflows/typing-heuristics.main.kts`. During typing validation, CI fails if the file is out of
sync with the actual mismatches — both when a new mismatch appears and when a listed mismatch has been resolved. This
makes improving the heuristics (developed separately) a visible, incremental process: every improvement removes entries
from this file.

To regenerate the file after changing typings or heuristics:

```shell
DUMP_TYPING_MISMATCHES=true .github/workflows/test.main.kts
```

Pull requests validate only the changed typings against the corresponding entries; the `main` branch and the nightly
run validate the whole file.
