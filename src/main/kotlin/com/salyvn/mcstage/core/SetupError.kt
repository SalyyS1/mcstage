package com.salyvn.mcstage.core

/**
 * Something about the request or the machine is wrong, and no server was started because of it: a
 * version that does not exist, no Java to run, a scenario file that does not parse, an EULA that was
 * not accepted. Separate from a failing test, because these two mean different things to a CI job and
 * so leave through different exit codes.
 */
class SetupError(message: String, cause: Throwable? = null) : Exception(message, cause)
