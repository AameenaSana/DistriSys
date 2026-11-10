plugins {
    `java-library`
}

// Pure math: no runtime dependencies at all, on purpose.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
