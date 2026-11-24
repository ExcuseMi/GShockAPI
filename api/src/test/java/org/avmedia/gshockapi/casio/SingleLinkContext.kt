package org.avmedia.gshockapi.casio

/** The single-watch link's setup and keep-alive, for the tests of the code that is not per link yet. */
internal val singleSetup: ConnectionSetup get() = WatchLink.connection.setup

internal val singleKeepAlive: ConnectionKeepAlive get() = WatchLink.connection.keepAlive
