#!/usr/bin/env kotlin
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.7.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:4.0.1")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v6")
@file:DependsOn("anomalyco:opencode__github:latest")

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.anomalyco.OpencodeGithub_Untyped
import io.github.typesafegithub.workflows.domain.Mode
import io.github.typesafegithub.workflows.domain.Permission
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.Cron
import io.github.typesafegithub.workflows.domain.triggers.Schedule
import io.github.typesafegithub.workflows.domain.triggers.WorkflowDispatch
import io.github.typesafegithub.workflows.dsl.expressions.expr
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.CheckoutActionVersionSource
import io.github.typesafegithub.workflows.yaml.DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG

workflow(
    name = "Update typings",
    on = listOf(
        Schedule(triggers = listOf(Cron(hour = "7", minute = "0"))),
        WorkflowDispatch(),
    ),
    consistencyCheckJobConfig = DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG.copy(
        checkoutActionVersion = CheckoutActionVersionSource.InferFromClasspath(),
    ),
    sourceFile = __FILE__,
) {
    job(
        id = "update_typings",
        runsOn = UbuntuLatest,
        permissions = mapOf(
            Permission.IdToken to Mode.Write,
            Permission.Contents to Mode.Write,
            Permission.PullRequests to Mode.Write,
        ),
    ) {
        uses(action = Checkout())
        run(
            name = "Debug",
            id = "debug",
            command = """gh run list --workflow .github/workflows/test.yaml --limit 1 --status completed --json conclusion --jq '.[0].conclusion // ""'""",
            env = mapOf(
                "GH_TOKEN" to expr("secrets.GITHUB_TOKEN"),
            ),
        )
        run(
            name = "Check if latest workflow run failed",
            id = "check_last_run",
            command = """
                CONCLUSION=$(gh run list --workflow .github/workflows/test.yaml --limit 1 --status completed --json conclusion --jq '.[0].conclusion // ""' 2>/dev/null)
                echo "conclusion=${'$'}CONCLUSION" >> "${'$'}GITHUB_OUTPUT"
            """.trimIndent(),
            env = mapOf(
                "GH_TOKEN" to expr("secrets.GITHUB_TOKEN"),
            ),
        )
        run(
            name = "Print conclusion",
            id = "print_conclusion",
            command = """
                echo "Conclusion: ${expr { "steps.check_last_run.outputs.conclusion" }}"
            """.trimIndent()
        )
        uses(
            action = OpencodeGithub_Untyped(
                model_Untyped = "opencode/big-pickle",
                useGithubToken_Untyped = "true",
                prompt_Untyped = "Run the /update-typings command",
            ),
            `if` = expr { "steps.check_last_run.outputs.conclusion == 'failure' || steps.check_last_run.outputs.conclusion == ''" },
            env = mapOf(
                "GITHUB_TOKEN" to expr("secrets.GITHUB_TOKEN"),
            ),
        )
    }
}
