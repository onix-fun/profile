package com.onix.profile.infra

import com.onix.profile.config.AppConfig
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
            poolName = "profile-postgres"
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
