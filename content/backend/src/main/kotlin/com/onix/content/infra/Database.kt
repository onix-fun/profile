package com.onix.content.infra

import com.onix.content.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Database {
    fun dataSource(config: AppConfig): DataSource? {
        val jdbcUrl = config.databaseJdbcUrl ?: return null
        val hikari = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = config.databaseUsername
            password = config.databasePassword
            maximumPoolSize = 8
            poolName = "content-postgres"
        }
        return HikariDataSource(hikari).also { ds ->
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate()
        }
    }
}
