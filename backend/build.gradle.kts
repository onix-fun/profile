plugins {
    base
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}

allprojects {
    group = "com.onix.profile"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

tasks.register("checkModuleBoundaries") {
    doLast {
        val allowed = mapOf(
            "domain" to emptySet(),
            "application" to setOf("domain"),
            "infrastructure" to setOf("application", "domain"),
            "app" to setOf("infrastructure", "application")
        )
        subprojects.forEach { module ->
            module.configurations.findByName("implementation")?.dependencies
                ?.withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                ?.forEach { dependency ->
                    check(dependency.path.removePrefix(":") in allowed.getValue(module.name)) {
                        "Forbidden module dependency ${module.name} -> ${dependency.path}"
                    }
                }
        }
    }
}

tasks.named("check") { dependsOn("checkModuleBoundaries") }
