plugins {
    java
    jacoco
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "dev.minecraft.prune"
version = "0.2.0"

val spigotVersion = project.findProperty("spigotVersion")?.toString() ?: "1.21.1-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:$spigotVersion")
    // sqlite-jdbc is shaded into the JAR (see shadowJar task below).
    // Querying CoreProtect's database.db directly means no api.enabled requirement.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.23.0")
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

// sqlite-jdbc shaded into the plugin JAR so servers don't need to provide it.
// Classes are relocated to avoid conflicts with any other plugin that bundles it.
tasks.jar {
    archiveClassifier.set("thin")
}
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "dev.minecraft.prune.shadow.sqlite")
    mergeServiceFiles()
}
tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

// ── Docker / server tasks ─────────────────────────────────────────────────────

val mcContainer  = System.getenv("MINECRAFT_CONTAINER") ?: "paper-test-server"
val mcWorld      = System.getenv("MINECRAFT_WORLD")     ?: "world"
val mcDataDir    = System.getenv("MINECRAFT_DATA_DIR")  ?: "${System.getenv("HOME")}/minecraft-test-server/data"
val pluginJar    = "build/libs/world-prune-plugin-${project.version}.jar"
val containerJar = "/data/plugins/world-prune-plugin-${project.version}.jar"

tasks.register<Exec>("serverStart") {
    description = "Start the test container via docker compose (creates it on first run)"
    group = "minecraft"
    // docker compose up -d is idempotent: no-op if already running, starts if stopped,
    // creates+starts if absent.  On first start itzg will download MODRINTH_PROJECTS
    // (e.g. CoreProtect) before launching the server, so we wait longer in that case.
    commandLine("bash", "-c", """
        if docker ps -q -f name=^/$mcContainer$ | grep -q .; then
          echo "$mcContainer is already running."
        else
          echo "Starting $mcContainer via docker compose..."          # Pre-seed CoreProtect CE config so it starts on first load.
          # CE requires project_branch=development to acknowledge it is not the
          # commercial build. We copy rather than bind-mount so CP can freely
          # rewrite its config without dirtying the repo fixture.
          # Note: api.enabled is NOT required — WorldPrune queries the SQLite DB directly.
          mkdir -p $mcDataDir/plugins/CoreProtect
          cp -n integration/fixtures/CoreProtect/config.yml $mcDataDir/plugins/CoreProtect/config.yml || true          MINECRAFT_DATA_DIR=$mcDataDir docker compose up -d
          echo "Waiting for server to be ready (~60 s, includes Modrinth downloads)..."
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

// ── Integration tests ───────────────────────────────────────────────────────

tasks.register<Exec>("integrationTest") {
    description = "Run the integration test suite against the running container (no rebuild)"
    group = "verification"
    commandLine("bash", "integration/run.sh")
    environment(mapOf(
        "MINECRAFT_CONTAINER" to mcContainer,
        "MINECRAFT_WORLD"     to mcWorld
    ))
}

tasks.register<Exec>("worldSeed") {
    description = "Create dummy far-from-spawn .mca files in the test world so there are always prune candidates"
    group = "verification"
    commandLine("bash", "integration/seed.sh")
    environment(mapOf(
        "MINECRAFT_CONTAINER" to mcContainer,
        "MINECRAFT_WORLD"     to mcWorld
    ))
}

tasks.register("integration") {
    description = "serverStart → deploy → serverReload → worldSeed → integrationTest"
    group = "verification"
    dependsOn("serverStart", "deploy", "serverReload", "worldSeed", "integrationTest")
}
tasks.named("deploy")      { mustRunAfter("serverStart") }
tasks.named("serverReload") { mustRunAfter("deploy") }
tasks.named("worldSeed")   { mustRunAfter("serverReload") }

// ── CoreProtect integration tests ────────────────────────────────────────────

tasks.register<Exec>("cpSeed") {
    description = "Seed the CoreProtect DB with fake activity and create dummy .mca fixture files (CoreProtect itself is deployed via MODRINTH_PROJECTS in docker-compose.yml)"
    group = "verification"
    commandLine("bash", "integration/cp-seed.sh")
    environment(mapOf(
        "MINECRAFT_CONTAINER" to mcContainer,
        "MINECRAFT_WORLD"     to mcWorld
    ))
}

tasks.register<Exec>("integrationTestCP") {
    description = "Run the CoreProtect integration test suite against the running container"
    group = "verification"
    commandLine("bash", "integration/cp-run.sh")
    environment(mapOf(
        "MINECRAFT_CONTAINER" to mcContainer,
        "MINECRAFT_WORLD"     to mcWorld
    ))
}

tasks.register("integrationCP") {
    description = "Full CoreProtect integration test flow: serverStart → deploy → cpSeed → integrationTestCP"
    group = "verification"
    dependsOn("serverStart", "deploy", "cpSeed", "integrationTestCP")
}
tasks.named("cpSeed")          { mustRunAfter("deploy") }
tasks.named("integrationTestCP") { mustRunAfter("cpSeed") }
tasks.named("integrationTest")   { mustRunAfter("worldSeed") }
