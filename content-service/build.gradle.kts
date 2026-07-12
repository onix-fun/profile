plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.protobuf") version "0.9.5"
    application
}

group = "com.onix"
version = "0.1.0-SNAPSHOT"

val ktorVersion = "2.3.13"
val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.insert-koin:koin-ktor:3.5.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.21.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.21.0")
    implementation("com.clickhouse:clickhouse-jdbc:0.7.2")
    implementation("com.rabbitmq:amqp-client:5.22.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("../dev/account-src/backend/src/main/proto")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.onix.content.MainKt")
    applicationName = "content-service"
}

tasks.test {
    useJUnitPlatform()
}
