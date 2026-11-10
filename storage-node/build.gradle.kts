plugins {
    application
}

application {
    mainClass = "dev.distrisys.storage.StorageNodeMain"
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)
    runtimeOnly(libs.grpc.netty) // the network transport; code only depends on the transport-neutral gRPC API

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
    testImplementation(libs.grpc.inprocess)
}

// A second launcher, bin/shard-cli, next to bin/storage-node in the installed distribution.
val shardCliScripts by tasks.registering(CreateStartScripts::class) {
    mainClass = "dev.distrisys.storage.ShardCli"
    applicationName = "shard-cli"
    outputDir = layout.buildDirectory.dir("shard-cli-scripts").get().asFile
    classpath = tasks.startScripts.get().classpath
}

distributions.main {
    contents { from(shardCliScripts) { into("bin") } }
}
