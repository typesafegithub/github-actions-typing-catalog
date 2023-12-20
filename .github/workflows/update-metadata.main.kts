#!/usr/bin/env kotlin
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:1.7.0")

import io.github.typesafegithub.workflows.actions.actions.CheckoutV4
import io.github.typesafegithub.workflows.domain.RunnerType
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.yaml.writeToFile

workflow(
    name = "Update metadata",
    sourceFile = __FILE__.toPath(),
    on = listOf(Push(branches = listOf("main"))),
) {
    job(
        id = "generate",
        runsOn = RunnerType.UbuntuLatest,
    ) {
        uses(action = CheckoutV4())
        run(
            name = "Configure git",
            command = """
                git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
                git config user.name "github-actions[bot]"
            """.trimIndent()
        )
        run(
            name = "Run generation logic",
            command = "scripts/updateMetadata.main.kts",
        )
        run(
            name = "Push new commit",
            command = "git push",
        )
    }
}.writeToFile()
