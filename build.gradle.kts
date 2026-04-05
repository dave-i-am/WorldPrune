plugins {
    java
    jacoco
}

group = "dev.minecraft.prune"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.108.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// ── Docker / server tasks ─────────────────────────────────────────────────────

val mcContainer  = System.getenv("MINECRAFT_CONTAINER") ?: "paper-test-server"
val mcWorld      = System.getenv("MINECRAFT_WORLD")     ?: "world"
val mcDataDir    = System.getenv("MINECRAFT_DATA_DIR")  ?: "${System.getenv("HOME")}/minecraft-test-server/data"
val pluginJar    = "build/libs/world-prune-plugin-${project.version}.jar"
val containerJar = "/data/plugins/world-prune-plugin-${project.version}.jar"

tasks.register<Exec>("serverStart") {
    description = "Start the test container, or create it if it does not exist"
    group = "minecraft"
    commandLine("bash", "-c", """
        if docker ps -q -f name=^/$mcContainer$ | grep -q .; then
          echo "$mcContainer is already running."
        elif docker ps -aq -f name=^/$mcContainer$ | grep -q .; then
          echo "Starting stopped container $mcContainer..."
          docker start $mcContainer
          echo "Waiting for server to be ready..."
          sleep 15
        else
          echo "Creating container $mcContainer..."
          mkdir -p $mcDataDir/plugins
          docker run -d \
            --name $mcContainer \
            -e EULA=TRUE -e TYPE=PAPER -e VERSION=1.21.1 \
            -e ONLINE_MODE=FALSE \
            -e ENABLE_RCON=true -e RCON_PASSWORD=minecraft -e RCON_PORT=25575 \
            -p 25565:25565 -v $mcDataDir:/data \
            itzg/minecraft-server:latest
          echo "Waiting for first-start (~60 s)..."
          sleep 60
        fi
    """.trimIndent())
}

tasks.register<Exec>("deploy") {
    description = "Build the JAR and copy it into the running container"
    group = "minecraft"
    dependsOn(tasks.named("build"))
    commandLine("docker", "cp", pluginJar, "$mcContainer:$containerJar")
}

tasks.register<Exec>("serverReload") {
    description = "Reload the plugin inside the running container via rcon"
    group = "minecraft"
    commandLine("bash", "-c", "docker exec $mcContainer rcon-cli reload confirm && sleep 2")
}

tasks.register<Exec>("rcon") {
    description = "Run an rcon command: ./gradlew rcon -Pargs=\"prune status\""
    group = "minecraft"
    val rconArgs = project.findProperty("args")?.toString() ?: ""
    commandLine("bash", "-c", "docker exec $mcContainer rcon-cli $rconArgs")
}

tasks.register<Exec>("logs") {
    description = "Tail the container logs"
    group = "minecraft"
    commandLine("docker", "logs", "-f", mcContainer)
}

// ── End-to-end tests ──────────────────────────────────────────────────────────

tasks.register<Exec>("e2eTest") {
    description = "Run the e2e test suite against the running container (no rebuild)"
    group = "verification"
    commandLine("bash", "e2e/run.sh")
    environment(mapOf(
        "MINECRAFT_CONTAINER" to mcContainer,
        "MINECRAFT_WORLD"     to mcWorld
    ))
}

tasks.register("e2e") {
    description = "serverStart → deploy → serverReload → e2eTest"
    group = "verification"
    dependsOn("serverStart", "deploy", "serverReload", "e2eTest")
}
tasks.named("deploy")      { mustRunAfter("serverStart") }
tasks.named("serverReload") { mustRunAfter("deploy") }
tasks.named("e2eTest")     { mustRunAfter("serverReload") }
