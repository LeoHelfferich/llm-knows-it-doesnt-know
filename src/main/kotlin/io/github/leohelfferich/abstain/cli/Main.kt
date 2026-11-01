package io.github.leohelfferich.abstain.cli

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

import kotlin.system.exitProcess

import io.github.leohelfferich.abstain.config.Workspace

/** Process entry point. The working directory is the repository root. */
fun main(args: Array<String>) {
    val cli = Cli(
        Workspace(Path.of("").toAbsolutePath()), System.out, System.err, System.getenv(),
        BufferedReader(InputStreamReader(System.`in`)),
    )
    exitProcess(cli.run(args))
}
