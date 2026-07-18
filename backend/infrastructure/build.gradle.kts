plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.protobuf")
}

val ktorVersion = "2.3.13"
val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"

dependencies {
    implementation(project(":application"))
    implementation(project(":domain"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.github.smiley4:ktor-swagger-ui:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.auth0:jwks-rsa:0.22.1")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.21.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.21.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.yaml:snakeyaml:2.3")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" } }
    generateProtoTasks { all().configureEach { plugins { create("grpc") } } }
}

sourceSets.main {
    proto {
        srcDir("../../api/proto")
        srcDir("../../.contracts/account/proto")
        srcDir("../../.contracts/search/proto")
    }
}
kotlin { jvmToolchain(21) }
