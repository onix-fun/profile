plugins {
    kotlin("jvm")
    application
}

val ktorVersion = "2.3.13"

dependencies {
    implementation(project(":infrastructure"))
    implementation(project(":application"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.grpc:grpc-netty:1.68.1")
    testImplementation(kotlin("test"))
    testImplementation(project(":domain"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

kotlin { jvmToolchain(21) }
application { mainClass.set("com.onix.profile.MainKt"); applicationName = "profile" }
