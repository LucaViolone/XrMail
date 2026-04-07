package com.xremail.backend.db

import com.xremail.backend.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Initializes the H2 embedded database and creates tables on startup.
 *
 * H2 is used for local token/session storage during development.
 * For production deployments, swap the JDBC URL to PostgreSQL or SQLite
 * by updating application.yaml — no code changes required.
 */
object DatabaseFactory {

    fun init(config: DatabaseConfig) {
        Database.connect(
            url = config.url,
            driver = config.driver,
        )

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                OAuthTokensTable,
            )
        }
    }
}
