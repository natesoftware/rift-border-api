plugins {
    `java-library`
    `maven-publish`
}

group = "com.natesoftware"
version = "4.1.1"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.12.0")
    // compileOnly is not on the test classpath, and the tests mock Plugin / World / scheduler
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// The licence travels with the binary, since consumers redistribute it inside their own jar.
tasks.withType<Jar>().configureEach {
    from(layout.projectDirectory.file("LICENSE")) { into("META-INF") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.javadoc {
    // The Javadoc voice carries no @param/@return tags, so doclint's "missing" group would flag every documented member
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.test {
    useJUnitPlatform()
    // Mockito's inline mock maker attaches an agent at runtime, which Java 21 warns about unless allowed up front
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "rift-border-api"
                description = "A volumetric circular shrinking border for Paper servers."
                url = "https://github.com/natesoftware/rift-border-api"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/natesoftware/rift-border-api/blob/main/LICENSE"
                    }
                }
            }
        }
    }
}
