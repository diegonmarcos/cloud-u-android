package com.diegonmarcos.superapp.cal

import com.diegonmarcos.superapp.core.DataBackendService

/**
 * [CalEngine] behind core's IDataBackend, shipped in Cloud-Lib-Cal.apk.
 *
 * The CalDAV config arrives as an argument on the methods that need it - the
 * app owns the credentials, this process never stores them.
 *
 * TWO APPS BIND THIS, NEITHER COMPILES IT (engine-apk-split, move 3): Cloud
 * Agenda by class name (CalBridge) and Cloud Me through the handshake
 * (CalEngineClient: the ENGINE action and the CONTRACT in the manifest, read
 * before binding). So the method list below only GROWS - a removed or reshaped
 * answer breaks every installed copy of both apps; a change that would do that
 * ships under a new method name with a higher CONTRACT.
 *
 * WHO GOES RED: lib-apks/test/test-engine-services.sh (exported, guarded,
 * findable, versioned, lists exactly what dispatch answers; Cloud Libs ship) and
 * the engine contract guard (every Cloud Me call is a listed method, and no app
 * declares or watches this module; every push).
 */
class CalBackendService : DataBackendService() {

    private val engine by lazy { CalEngine(applicationContext) }

    override fun methodNames(): Array<String> = arrayOf(
        "calendars", "events", "sync", "projects", "todos",
        "saveTodo", "setTodoStatus", "deleteTodo", "syncTodos", "testCaldav",
        "seed", "hasData",
    )

    override fun dispatch(method: String, args: Array<String>): String = when (method) {
        "calendars" -> engine.calendars()
        "events"    -> engine.events(
            args.getOrNull(0)?.toLongOrNull() ?: 0L,
            args.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE,
        )
        "sync"      -> engine.sync()
        "projects"  -> engine.projects()
        "todos"     -> engine.todos(args.getOrNull(0).orEmpty())
        "saveTodo"  -> engine.saveTodo(args.getOrNull(0).orEmpty(), args.getOrNull(1))
        "setTodoStatus" -> engine.setTodoStatus(
            args.getOrNull(0).orEmpty(),
            args.getOrNull(1)?.toBoolean() ?: false,
            args.getOrNull(2),
        )
        "deleteTodo" -> engine.deleteTodo(args.getOrNull(0).orEmpty(), args.getOrNull(1))
        "syncTodos"  -> engine.syncTodos(args.getOrNull(0))
        "testCaldav" -> engine.testCaldav(args.getOrNull(0))
        // Cutover handoff for tasks a re-sync cannot rebuild - see CalEngine.seed.
        "seed"       -> engine.seed(args.getOrNull(0).orEmpty())
        "hasData"    -> engine.hasData()
        else -> throw IllegalArgumentException("unknown method: $method")
    }
}
