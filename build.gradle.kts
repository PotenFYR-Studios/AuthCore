// ============================================================================
// AuthCore - central build script (shared by every Stonecutter variant).
//
// This file is evaluated once per generated variant project in versions/<mc>-<loader>/.
// Everything that differs per variant is derived from `stonecutter.current`:
//   - version group / range label (jar naming)
//   - Java level + mixin compatibility level
//   - loader-specific dependencies
//
// Stonecraft wires the game/loader/mappings dependencies from the per-version
// files in versions/dependencies/<mc>.properties - this file only adds the
// third-party libraries and loader-specific extras.
// ============================================================================

import gg.meza.stonecraft.mod

plugins {
    id("gg.meza.stonecraft")
}

// ----------------------------------------------------------------------------
// Variant identification
// ----------------------------------------------------------------------------

// Loader name = the part after the last dash of the variant directory name,
// e.g. versions/1.21.11-fabric -> "fabric".
val loader: String = project.name.substringAfterLast("-")

// Range label of the released jar, e.g. "1.19-1.21" for the 1.21.11 build.
// A single jar supports the whole range - verified by the host-test harness
// (tools/host-tests) on every range endpoint.
val rangeLabel: String =
    when {
        stonecutter.current.parsed < "1.19" -> "1.16-1.18"
        stonecutter.current.parsed < "26" -> "1.19-1.21"
        else -> "26.x"
    }

val modId: String = property("mod.id") as String
val modVersion: String = property("mod.version") as String
val minecraftVersion: String = stonecutter.current.version

version = modVersion
group = property("mod.group") as String

// Released jar name: authcore-<range>-<loader>-<modversion>.jar
base { archivesName.set("$modId-$rangeLabel-$loader") }

// ----------------------------------------------------------------------------
// Stonecraft settings
// ----------------------------------------------------------------------------

modSettings {
    // Headless-friendly test client options (avoids the "eyes and ears blown
    // out" defaults when running the client variant in CI).
    clientOptions {
        fov = 90
        guiScale = 3
        narrator = false
        darkBackground = true
        musicVolume = 0.0
    }

    // Shared run directory for every variant (kept out of the version dirs).
    runDirectory = rootProject.layout.projectDirectory.dir("run")

    // GameTest junit reports land in each variant's build dir.
    fabricClientJunitReportLocation = project.layout.buildDirectory.file("fabric-client-junit-report.xml")

    // Per-variant placeholders expanded into resources (fabric.mod.json etc.).
    // NOTE: fabric.mod.json is parsed by Loom at configure time, so it must stay
    // valid JSON - Stonecutter preprocessor comments are NOT allowed there.
    variableReplacements =
        mapOf(
            "javaVersion" to
                when {
                    stonecutter.current.parsed < "1.20.5" -> ">=17"
                    stonecutter.current.parsed < "26" -> ">=21"
                    else -> ">=25"
                },
            "mixinCompatibilityLevel" to
                when {
                    stonecutter.current.parsed < "1.17" -> "JAVA_16"
                    stonecutter.current.parsed < "1.20.5" -> "JAVA_17"
                    stonecutter.current.parsed < "26" -> "JAVA_21"
                    else -> "JAVA_25"
                },
        )
}

// ----------------------------------------------------------------------------
// Stonecutter constants + Java level
// ----------------------------------------------------------------------------

stonecutter {
    // Loader conditionals ("fabric", "forge", "neoforge", "forgeLike", "fabricLike")
    // are registered automatically by Stonecraft - usable in code as  /*? if fabric {*/

    java {
        // Java level follows the Minecraft version of each group:
        //   G1 (1.16-1.18): Java 17   G2 (1.19-1.21): Java 21   G3 (26.x): Java 25
        val javaMajor =
            when {
                stonecutter.current.parsed < "1.17" -> 16
                stonecutter.current.parsed < "1.20.5" -> 17
                stonecutter.current.parsed < "26" -> 21
                else -> 25
            }
        sourceCompatibility = JavaVersion.toVersion(javaMajor)
        targetCompatibility = JavaVersion.toVersion(javaMajor)
        toolchain { languageVersion.set(JavaLanguageVersion.of(javaMajor)) }
    }
}

// ----------------------------------------------------------------------------
// Dependencies
// ----------------------------------------------------------------------------

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev")
    maven("https://maven.minecraftforge.net")
    maven("https://maven.neoforged.net/releases/")
    // Floodgate (GeyserMC) API
    maven("https://repo.opencollab.dev/main/")
    // Velocity / BungeeCord proxy APIs (Paper / md-5)
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.md-5.net/content/repositories/public/")
}

val shaded: Configuration = configurations.include.get()

dependencies {
    // Fabric loader + API are wired by Stonecraft from versions/dependencies
    // (the full fabric-api jar already contains every module, e.g. entity events).

    // --- shaded runtime libraries (bundled inside the jar) -------------------
    // Loom's `include` configuration bundles the dependency into the jar; the
    // `!!` unwraps the nullable Dependency returned by the Kotlin DSL accessor.
    implementation(include("com.mysql:mysql-connector-j:${property("mysql_version")}")!!)
    implementation(include("org.xerial:sqlite-jdbc:${property("sqlite_version")}")!!)
    implementation(include("org.postgresql:postgresql:${property("postgres_version")}")!!)
    implementation(include("redis.clients:jedis:${property("jedis_version")}")!!)
    implementation(include("org.apache.commons:commons-pool2:${property("commons_pool2_version")}")!!)
    implementation(include("org.bouncycastle:bcpkix-jdk18on:${property("bouncycastle_version")}")!!)
    implementation(include("org.bouncycastle:bcprov-jdk18on:${property("bouncycastle_version")}")!!)
    // bcpkix's module-info requires the org.bouncycastle.util module - forge 1.18.x
    // resolves the jar-in-jar dependencies through JPMS and fails without it.
    implementation(include("org.bouncycastle:bcutil-jdk18on:${property("bouncycastle_version")}")!!)
    // Forge 1.18.x resolves nested jars through JPMS and the game already provides a
    // com.google.gson module - a second one fails module resolution. The other loaders
    // tolerate the shaded copy, so only forge drops it (the game's gson is API-compatible
    // for everything AuthCore uses).
    if (!mod.isForge) {
        implementation(include("com.google.code.gson:gson:${property("gson_version")}")!!)
    }
    implementation(include("net.kyori:option:${property("option_version")}")!!)
    implementation(include("io.leangen.geantyref:geantyref:${property("geantyref_version")}")!!)
    implementation(include("org.jetbrains.kotlin:kotlin-stdlib:${property("kotlin_version")}")!!)
    implementation(include("org.spongepowered:configurate-core:${property("configurate_core_version")}")!!)
    implementation(include("org.spongepowered:configurate-hocon:${property("configurate_hocon_version")}")!!)
    implementation(include("com.password4j:password4j:${property("password4j_version")}")!!)
    implementation(include("com.j256.two-factor-auth:two-factor-auth:${property("two_factor_auth_version")}")!!)

    // --- compile-only APIs (provided by the server, never bundled) ------------
    compileOnly("org.geysermc.floodgate:api:${property("floodgate_version")}")
    compileOnly("net.luckperms:api:${property("luckperms_version")}")
    // Proxy plugin APIs - the same jar doubles as a BungeeCord / Velocity plugin.
    compileOnly("net.md-5:bungeecord-api:${property("bungeecord_api_version")}")
    compileOnly("com.velocitypowered:velocity-api:${property("velocity_api_version")}")
}

// ----------------------------------------------------------------------------
// Loader-specific sources
// ----------------------------------------------------------------------------
// Thin entrypoints live in per-loader source roots so they only ever compile for
// their own loader (no Stonecutter comment gymnastics needed):
//   src/fabric/java     net.ded3ec.entrypoint.FabricEntry
//   src/forge/java      net.ded3ec.entrypoint.ForgeEntry
//   src/neoforge/java   net.ded3ec.entrypoint.NeoForgeEntry
sourceSets.main.get().java.srcDir(
    when {
        mod.isForge -> rootProject.file("src/forge/java")
        mod.isNeoforge -> rootProject.file("src/neoforge/java")
        else -> rootProject.file("src/fabric/java")
    }
)

// ----------------------------------------------------------------------------
// Build + collect
// ----------------------------------------------------------------------------

// The client companion (login screen + client mixins) is fabric-only today: the classes are
// gated out of forge-like variants, so their mixin config must not ship there either.
if (!mod.isFabric) {
    project.tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        exclude("authcore.client.mixins.json")
    }
}

// Copies the built jar of every variant into the root build/libs so a single
// `gradlew build` over all versions collects every released artifact in one
// place (used by the release pipeline and the host-test harness).
val collectJars = tasks.register<Copy>("collectJars") {
    group = "build"
    from(provider { tasks.named("remapJar") })
    into(rootProject.layout.buildDirectory.dir("libs"))
    dependsOn("build")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:-deprecation")
}
