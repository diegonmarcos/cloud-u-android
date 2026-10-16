package com.diegonmarcos.cloudagenda.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The schema as an ordered list of steps. A fresh install runs every step from 0; an install at version N runs the
 * steps above N. There is no other way a table changes, and no step drops or rewrites user rows: a step adds
 * tables, columns or indexes. (MigrationsTest holds both: a v1 database with data upgrades with the data intact,
 * and no step carries a DROP TABLE / DELETE.)
 */
object Migrations {
    class Step(val to: Int, val statements: List<String>)

    private val V1 = listOf(
        """CREATE TABLE IF NOT EXISTS projects (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            position INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS lists (
            id TEXT PRIMARY KEY NOT NULL,
            project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            position INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS tasks (
            id TEXT PRIMARY KEY NOT NULL,
            list_id TEXT NOT NULL REFERENCES lists(id) ON DELETE CASCADE,
            parent_id TEXT REFERENCES tasks(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            details TEXT NOT NULL DEFAULT '',
            date_target TEXT,
            date_deadline TEXT,
            location TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'action',
            position INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS attachments (
            id TEXT PRIMARY KEY NOT NULL,
            task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            uri TEXT NOT NULL,
            label TEXT NOT NULL DEFAULT '',
            position INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS tags (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL COLLATE NOCASE UNIQUE,
            color INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS task_tags (
            task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
            tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY (task_id, tag_id))""",
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_lists_project ON lists(project_id)",
        "CREATE INDEX IF NOT EXISTS idx_tasks_list ON tasks(list_id)",
        "CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks(parent_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_task ON attachments(task_id)",
        "CREATE INDEX IF NOT EXISTS idx_task_tags_tag ON task_tags(tag_id)",
    )

    /** The mail agent's declaration (one row) and the proposals it makes, which wait for the user's decision. */
    private val V2 = listOf(
        """CREATE TABLE IF NOT EXISTS agent_config (
            id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1),
            enabled INTEGER NOT NULL DEFAULT 0,
            source TEXT NOT NULL DEFAULT 'cloud-mail',
            account TEXT NOT NULL DEFAULT '',
            jmap_url TEXT NOT NULL DEFAULT '',
            jmap_auth TEXT NOT NULL DEFAULT 'none',
            mailboxes TEXT NOT NULL DEFAULT '',
            frequency_minutes INTEGER NOT NULL DEFAULT 360,
            agent TEXT NOT NULL DEFAULT '',
            model TEXT NOT NULL DEFAULT '',
            extract_events INTEGER NOT NULL DEFAULT 1,
            extract_bookings INTEGER NOT NULL DEFAULT 1,
            extract_invoices INTEGER NOT NULL DEFAULT 1,
            extract_deadlines INTEGER NOT NULL DEFAULT 1,
            target_list_id TEXT NOT NULL DEFAULT '',
            last_run_at INTEGER NOT NULL DEFAULT 0,
            last_status TEXT NOT NULL DEFAULT '',
            last_detail TEXT NOT NULL DEFAULT '')""",
        """CREATE TABLE IF NOT EXISTS proposals (
            id TEXT PRIMARY KEY NOT NULL,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            details TEXT NOT NULL DEFAULT '',
            date_target TEXT,
            date_deadline TEXT,
            location TEXT NOT NULL DEFAULT '',
            source_ref TEXT NOT NULL DEFAULT '',
            source_subject TEXT NOT NULL DEFAULT '',
            confidence REAL NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at INTEGER NOT NULL DEFAULT 0,
            decided_at INTEGER NOT NULL DEFAULT 0,
            task_id TEXT NOT NULL DEFAULT '')""",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_proposals_dedupe ON proposals(source_ref, title)",
    )

    val STEPS: List<Step> = listOf(Step(1, V1), Step(2, V2))

    val LATEST: Int = STEPS.last().to

    /** Runs every step above [from] up to and including [to], in one transaction. */
    fun apply(db: SQLiteDatabase, from: Int, to: Int = LATEST) {
        db.beginTransaction()
        try {
            for (step in STEPS.sortedBy { it.to }) {
                if (step.to > from && step.to <= to) step.statements.forEach { db.execSQL(it) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/**
 * The app's one database. [name] null = in memory (tests). Upgrades go through [Migrations]; a downgrade (an older
 * build over a newer file) leaves the file alone instead of wiping it.
 */
class TasksDb(context: Context, name: String? = FILE) : SQLiteOpenHelper(context.applicationContext, name, null, Migrations.LATEST) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = Migrations.apply(db, 0)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Migrations.apply(db, oldVersion, newVersion)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never wipe user data: the later schema only added things, so the older code simply ignores them. Every step is IF NOT EXISTS, so the newer build re-running its steps over this file is harmless.
    }

    companion object {
        const val FILE = "tasks.db"

        @Volatile private var shared: TasksDb? = null

        /** The one helper of the process: the UI, the AI tool and the worker share its connection instead of racing three. */
        fun shared(context: Context): TasksDb = shared ?: synchronized(this) { shared ?: TasksDb(context).also { shared = it } }
    }
}
