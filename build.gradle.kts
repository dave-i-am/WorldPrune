plugins {
    java
    jacoco
    checkstyle
    id("com.gradleup.shadow") version "9.0.0-beta4"
    id("com.github.spotbugs") version "6.5.1"
}

group = "dev.minecraft.prune"
version = "0.3.0-beta.2"

val spigotVersion = project.findProperty("spigotVersion")?.toString() ?: "1.20-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:$spigotVersion")
    // sqlite-jdbc is shaded into the JAR (see shadowJar task below).
    // Querying CoreProtect's database.db directly means no api.enabled requirement.
    implementation("org.xerial:sqlite-jdbc:3.53.0.0")

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

checkstyle {
    toolVersion = "10.21.4"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

spotbugs {
    toolVersion = "4.9.3"
    excludeFilter = file("config/spotbugs/exclude.xml")
    ignoreFailures = false
}
tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports.create("html") { required = true }
    reports.create("xml")  { required = false }
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
    // sqlite-jdbc ships native libs for every platform; trim the implausible ones.
    // Java 17+ has no 32-bit JVM, so x86 natives can never load on a modern server.
    exclude("**/native/FreeBSD/**")
    exclude("**/native/Linux-Android/**")
    exclude("**/native/Linux/ppc64/**")
    exclude("**/native/Linux/riscv64/**")
    exclude("**/native/Linux/x86/**")
    exclude("**/native/Linux-Musl/x86/**")
    exclude("**/native/Windows/x86/**")
    exclude("**/native/Windows/armv7/**")
}
tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

// ── Docker / server tasks ─────────────────────────────────────────────────────

val mcContainer  = System.getenv("MINECRAFT_CONTAINER") ?: "paper-test-server"
val mcWorld      = System.getenv("MINECRAFT_WORLD")     ?: "world"
val pluginJar    = "build/libs/world-prune-plugin-${project.version}.jar"
val containerJar = "/data/plugins/world-prune-plugin-${project.version}.jar"

tasks.register<Exec>("serverStart") {
    description = "Start the test container via docker compose (creates it on first run)"
    group = "minecraft"
    commandLine("bash", "-c", """
        if docker ps -q -f name=^/$mcContainer$ | grep -q .; then
          echo "$mcContainer is already running."
        else
          echo "Starting $mcContainer via docker compose..."
          # Always tear down first so stale anonymous volumes (e.g. left-over
          # *.download temp files from a crashed previous run) don't prevent
          # Modrinth plugin downloads from succeeding.
          docker compose down --remove-orphans --volumes 2>/dev/null || true
          docker compose up -d
          # Seed CoreProtect CE config immediately after container starts.
          # /data is available as soon as the container runs; we copy before Paper
          # loads plugins so CoreProtect sees project_branch=development on first boot.
          until docker exec $mcContainer test -d /data 2>/dev/null; do sleep 1; done
          docker exec -u 1000:1000 $mcContainer mkdir -p /data/plugins/CoreProtect
          docker cp integration/fixtures/CoreProtect/config.yml $mcContainer:/data/plugins/CoreProtect/config.yml
          docker exec $mcContainer chown 1000:1000 /data/plugins/CoreProtect/config.yml
          # Poll for RCON readiness instead of fixed sleep.
          # First-start may take 3+ minutes (Paper JAR remap + Modrinth downloads).
          echo "Waiting for server RCON to be ready (up to 5 min)..."
          deadline=$(( ${'$'}(date +%s) + 300 ))
          until docker exec $mcContainer rcon-cli list >/dev/null 2>&1; do
            if [ ${'$'}(date +%s) -ge ${'$'}deadline ]; then
              echo "ERROR: server did not become ready within 5 minutes"
              docker logs --tail 50 $mcContainer
              exit 1
            fi
            printf '.'
            sleep 5
          done
          echo " ready."
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
    description = "Restart the container to pick up the deployed plugin JAR"
    group = "minecraft"
    commandLine("bash", "-c", """
        echo "Restarting $mcContainer to pick up new plugin JAR..."
        docker compose restart
        echo "Waiting for server RCON to be ready (up to 5 min)..."
        deadline=$(( ${'$'}(date +%s) + 300 ))
        until docker exec $mcContainer rcon-cli list >/dev/null 2>&1; do
          if [ ${'$'}(date +%s) -ge ${'$'}deadline ]; then
            echo "ERROR: server did not become ready after restart"
            docker logs --tail 50 $mcContainer
            exit 1
          fi
          printf '.'
          sleep 5
        done
        echo " ready."
    """.trimIndent())
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
    finalizedBy("serverStop")
}

tasks.register<Exec>("seed") {
    description = "Seed all integration test fixtures (prune-candidate regions + CoreProtect DB)"
    group = "verification"
    commandLine("bash", "integration/seed.sh")
    environment(mapOf(
        "MINECRAFT_CONTAINER" to mcContainer,
        "MINECRAFT_WORLD"     to mcWorld
    ))
}

tasks.register<Exec>("serverStop") {
    description = "Destroy the test container (always run at end of integration for a clean next start)"
    group = "minecraft"
    isIgnoreExitValue = true
    commandLine("bash", "-c", "docker compose down --remove-orphans --volumes || true")
}

tasks.register("integration") {
    description = "Full integration suite: serverStart → deploy → serverReload (container restart) → seed → integrationTest → serverStop"
    group = "verification"
    dependsOn("serverStart", "deploy", "serverReload", "seed", "integrationTest")
    finalizedBy("serverStop")
}

// ── Ordering constraints ──────────────────────────────────────────────────────
tasks.named("deploy")          { mustRunAfter("serverStart") }
tasks.named("serverReload")    { mustRunAfter("deploy") }
tasks.named("seed")            { mustRunAfter("serverReload") }
tasks.named("integrationTest") { mustRunAfter("seed") }

// ── Developer setup ───────────────────────────────────────────────────────────
tasks.register<Exec>("installHooks") {
    description = "Point git core.hooksPath at .githooks/ and make all hooks executable"
    group = "setup"
    commandLine("bash", "-c",
        "git config core.hooksPath .githooks && chmod +x .githooks/pre-commit .githooks/commit-msg && echo 'Hooks installed: pre-commit, commit-msg'")
}
