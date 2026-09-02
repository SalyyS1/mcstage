package com.salyvn.mcstage.run

import com.salyvn.mcstage.instance.CreateRequest
import com.salyvn.mcstage.instance.Instance
import com.salyvn.mcstage.instance.InstanceCreator
import java.nio.file.Path

/**
 * Where the runner gets a server directory for one case.
 *
 * Two answers exist and they are the difference between the two commands. `test` wants a directory made
 * for the platform and version the scenario named, downloading the build if it has to. `run` already has
 * a directory and must not touch what is in it beyond the plugins it was told to install. Both then take
 * exactly the same path through boot, steps and shutdown, which is the point of the split.
 */
interface CasePreparer {

    fun prepare(case: Scenario.Case, scenario: Scenario, port: Int, onEvent: (String) -> Unit): Prepared

    /** @param facts what to report about this instance, such as which build it turned out to be. */
    data class Prepared(
        val instance: Instance,
        val facts: Map<String, String> = emptyMap(),
        val notes: List<String> = emptyList(),
    )
}

/** Creates, or brings up to date, an instance per case under one working directory. */
class CreatingPreparer(
    private val creator: InstanceCreator,
    private val workRoot: Path,
    private val acceptEula: Boolean,
) : CasePreparer {

    override fun prepare(
        case: Scenario.Case,
        scenario: Scenario,
        port: Int,
        onEvent: (String) -> Unit,
    ): CasePreparer.Prepared {
        val created = creator.create(
            dir = workRoot.resolve(case.slug),
            request = CreateRequest(
                platform = case.platform,
                version = case.version,
                port = port,
                memory = scenario.memory,
                acceptEula = acceptEula,
                plugins = scenario.plugins,
                properties = scenario.properties,
                allowPrerelease = scenario.allowPrerelease,
            ),
            onEvent = onEvent,
        )
        return CasePreparer.Prepared(
            instance = created.instance,
            facts = buildMap {
                put("build", "${created.descriptor.version} build ${created.descriptor.build}")
                put("jar", "${created.descriptor.jarFileName} (${created.descriptor.checksum})")
                if (created.plugins.isNotEmpty()) put("plugins", created.plugins.joinToString(", "))
            },
            notes = created.notes,
        )
    }
}

/**
 * Uses a directory that already exists, as `mcstage run` does.
 *
 * Plugins named on the command line are still copied in, because installing the jar that was just built
 * and then booting is the loop this is for. Nothing else about the instance is rewritten: its properties
 * are whatever the user left there.
 */
class ExistingPreparer(private val instance: Instance) : CasePreparer {

    override fun prepare(
        case: Scenario.Case,
        scenario: Scenario,
        port: Int,
        onEvent: (String) -> Unit,
    ): CasePreparer.Prepared {
        val descriptor = instance.descriptor()
        val plugins = instance.installPlugins(scenario.plugins)
        return CasePreparer.Prepared(
            instance = instance,
            facts = buildMap {
                put("build", "${descriptor.version} build ${descriptor.build}")
                put("jar", "${descriptor.jarFileName} (${descriptor.checksum})")
                if (plugins.isNotEmpty()) put("plugins", plugins.joinToString(", "))
            },
        )
    }
}
