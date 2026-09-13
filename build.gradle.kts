plugins {
    java
}

group = "top.cheesesmp"
version = "1.0.0"

val velocityVersion = "3.4.0"
val nettyVersion = "4.2.7.Final"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityVersion")

    // Shipped with Velocity at runtime - never shaded.
    compileOnly("org.yaml:snakeyaml:1.33")
    // Only needed to hold a player on the proxy; Velocity provides netty at runtime.
    compileOnly("io.netty:netty-transport:$nettyVersion")
    compileOnly("io.netty:netty-common:$nettyVersion")

    testImplementation("com.velocitypowered:velocity-api:$velocityVersion")
    testImplementation("org.yaml:snakeyaml:1.33")
    testImplementation("io.netty:netty-transport:$nettyVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Velocity 3.x needs Java 17 bytecode; any JDK 17+ can produce it via --release.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

// Surface deprecation details instead of the "uses or overrides a deprecated API" note.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Xlint:deprecation")
}
