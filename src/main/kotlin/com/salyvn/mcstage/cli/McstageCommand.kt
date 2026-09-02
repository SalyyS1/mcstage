package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.api.BuildIndexes
import com.salyvn.mcstage.cache.JarCache
import com.salyvn.mcstage.core.DownloadFailed
import com.salyvn.mcstage.core.Http
import com.salyvn.mcstage.core.SetupError

/**
 * The options every command that can reach the network shares, so that `--offline` and `--cache` mean the
 * same thing everywhere and a CI job can set them once.
 *
 * It also turns the two failures that are nobody's fault — a version that does not exist, a download that
 * did not arrive — into one line on stderr and exit code 2. A stack trace tells the user about this tool;
 * a sentence tells them about their problem.
 */
abstract class McstageCommand(name: String) : CliktCommand(name = name) {

    protected val cacheDir by option(
        "--cache",
        help = "Directory for downloaded server jars (default: \$MCSTAGE_HOME/jars or ~/.mcstage/jars)",
    ).path()

    protected val offline by option(
        "--offline",
        help = "Never reach the network: use what is already in the cache or fail",
    ).flag()

    protected val http: Http by lazy { Http(offline = offline) }

    protected val cache: JarCache by lazy {
        JarCache(root = cacheDir ?: JarCache.defaultRoot(), http = http, offline = offline)
    }

    protected val indexes: BuildIndexes by lazy { BuildIndexes(http) }

    final override fun run() {
        try {
            execute()
        } catch (e: SetupError) {
            fail(e.message)
        } catch (e: DownloadFailed) {
            fail(e.message)
        } catch (e: NoSuchElementException) {
            fail(e.message)
        } catch (e: IllegalArgumentException) {
            fail(e.message)
        }
    }

    protected abstract fun execute()

    private fun fail(message: String?): Nothing {
        echo(message ?: "The command could not be carried out.", err = true)
        throw ProgramResult(SETUP_FAILED)
    }

    protected companion object {
        /** Kept distinct from 1, which means a server disagreed with a scenario. */
        const val SETUP_FAILED = 2
    }
}
