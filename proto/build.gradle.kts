plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    // `api`: modules that depend on :proto see the generated types and gRPC stubs.
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                // omit the @Generated annotation so we don't need javax.annotation on the classpath
                create("grpc") { option("@generated=omit") }
            }
        }
    }
}
