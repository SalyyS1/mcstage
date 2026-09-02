package com.salyvn.mcstage.api

import com.salyvn.mcstage.core.ArtifactSource
import com.salyvn.mcstage.core.BuildIndex
import com.salyvn.mcstage.core.Http
import com.salyvn.mcstage.core.Platform

/** Picks the client that knows a platform, so nothing above this layer names an API. */
open class BuildIndexes(private val http: Http) {

    private val fill by lazy { FillClient(http) }
    private val purpur by lazy { PurpurClient(http) }

    open fun of(platform: Platform): BuildIndex = when (platform.source) {
        ArtifactSource.FILL -> fill
        ArtifactSource.PURPUR -> purpur
    }
}
