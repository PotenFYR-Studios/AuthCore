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
import java.util.Properties

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
// A single jar supports the whole range - verified by the Docker host-test
// harness (test/docker) on every range endpoint.
//
// FUTURE-SNAPSHOT SUPPORT: everything from 26.x on is the unobfuscated era with stable
// Mojang names, so a FUTURE major line (e.g. building against a 27.0 snapshot) is
// detected dynamically and gets its own open-ended label ("<version>+") instead of
// being silently mislabeled as 26.1-26.2. The three known groups keep their historical
// jar names so the host-test harness and release tooling stay stable.
// NOTE: stonecutter.current.parsed is a ParsedVersion (comparable against strings);
// it must NOT be annotated as String.
val parsedVersion = stonecutter.current.parsed
val modernEra: Boolean = parsedVersion >= "26"
val knownModernLine: Boolean = parsedVersion < "27"
val rangeLabel: String =
    when {
        parsedVersion < "1.19" -> "1.16-1.18"
        parsedVersion < "26" -> "1.19-1.21"
        knownModernLine -> "26.1-26.2"
        else -> "$parsedVersion+"
    }

// Minecraft version range declared in fabric.mod.json / mods.toml, so Modrinth
// resolves the exact supported versions instead of "*" (which Modrinth reads as
// "all versions"). Mirrors `rangeLabel` above and the ranges verified by
// the test/docker harness.
//   - Fabric (fabric.mod.json depends.minecraft): Modrinth matches this with
//     npm-semver `satisfies`; a bare dash like "1.19-1.21.11" parses as a
//     prerelease tag and matches NOTHING (Modrinth then pre-selects no versions
//     and the grid shows the full list). ">=1.19 <=1.21.11" is valid for both
//     npm-semver and the Fabric loader's own VersionPredicateParser.
//   - Forge/NeoForge (mods.toml versionRange): maven bracket syntax "[min,max]".
//
// FORWARD-COMPATIBILITY: the 26.x group is UNBOUNDED on the upper end
// (">=26.1" / "[26.1,)"). The unobfuscated era ships stable Mojang names, so a
// newer 26.x release or snapshot boots this jar with the standard untested-version
// warning banner instead of the loader rejecting it outright. The intermediary-era
// groups keep exact upper bounds - their remapped call sites can silently break on
// unmapped versions, so an open range would be unsafe there.
val minecraftRangeMin: String =
    when {
        parsedVersion < "1.19" -> "1.16"
        parsedVersion < "26" -> "1.19"
        knownModernLine -> "26.1"
        else -> parsedVersion.toString() // future line: the built version is the minimum
    }
val minecraftRangeMax: String? =
    when {
        parsedVersion < "1.19" -> "1.18.2"
        parsedVersion < "26" -> "1.21.11"
        else -> null // unobfuscated era: every future version of the line is allowed
    }
val minecraftRange: String =
    if (minecraftRangeMax == null) ">=$minecraftRangeMin"
    else ">=$minecraftRangeMin <=$minecraftRangeMax"
val minecraftRangeForge: String =
    if (minecraftRangeMax == null) "[$minecraftRangeMin,)"
    else "[$minecraftRangeMin,$minecraftRangeMax]"

// Dependency pins of the current variant (versions/dependencies/<mc>.properties)
// - used to declare accurate loader/API requirements in fabric.mod.json.
val variantDependencies = Properties().apply {
    rootProject.file("versions/dependencies/${stonecutter.current.version}.properties")
        .inputStream().use { load(it) }
}

// Precise loader minimums (maven range syntax) declared in mods.toml so the loader
// resolves the exact supported range. IMPORTANT: the minimum is the FIRST version that
// supports the group's LOWEST Minecraft version - never the build target, otherwise a
// server on an in-range version (e.g. 26.1 with the 26.2-built jar) is rejected.
//   G1 (1.16-1.18): forge 36.1.0+ (1.16.5 line)
//   G2 (1.19-1.21): forge 41.1.0+ (1.19 line), neoforge 20.2.59-beta+ (1.20.2 line)
//   G3 (26.1-26.2): neoforge 26.1.0+ (26.1 line)
val forgeRange: String =
    if (parsedVersion < "1.19") "[36.1.0,)"
    else "[41.1.0,)"
val neoforgeRange: String =
    when {
        parsedVersion < "26" -> "[20.2.59-beta,)"
        knownModernLine -> "[26.1.0,)"
        else -> "[$parsedVersion,)" // future line: minimum is the built version
    }
val fabricLoaderRange: String =
    when {
        parsedVersion < "1.19" -> ">=0.10.8"
        parsedVersion < "26" -> ">=0.14.24"
        else -> ">=0.16.0"
    }

// Fabric API minimum per group (the first version supporting the group's LOWEST
// Minecraft version - never the build target, otherwise in-range servers are rejected):
//   G1 (1.16-1.18): fabric-api 0.28.x (1.16.5 line)
//   G2 (1.19-1.21): fabric-api 0.59.x (1.19 line)
//   G3 (26.1-26.2): fabric-api 0.134.x (26.1 line)
val fabricApiRange: String =
    when {
        parsedVersion < "1.19" -> ">=0.28.0"
        parsedVersion < "26" -> ">=0.59.0"
        else -> ">=0.134.0"
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
    // Shared run directory for every variant (kept out of the version dirs).
    runDirectory = rootProject.layout.projectDirectory.dir("run")

    // Per-variant placeholders expanded into resources (fabric.mod.json etc.).
    // NOTE: fabric.mod.json is parsed by Loom at configure time, so it must stay
    // valid JSON - Stonecutter preprocessor comments are NOT allowed there.
    variableReplacements =
        mapOf(
            "minecraftRange" to minecraftRange,
            "minecraftRangeForge" to minecraftRangeForge,
            "fabricLoaderVersion" to fabricLoaderRange,
            "fabricApiVersion" to fabricApiRange,
            "forgeRange" to forgeRange,
            "neoforgeRange" to neoforgeRange,
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
        //   G1 (1.16-1.18): Java 17   G2 (1.19-1.21): Java 21   G3 (26.1-26.2): Java 25
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
    // Forge AND NeoForge resolve nested jars through the JPMS module layer, where the game
    // already provides a com.google.gson module - a second one makes the layer ambiguous
    // ("Module mysql.connector.j reads more than one module named com.google.gson") and the
    // server fails to boot. The other loaders tolerate the shaded copy, so only forge-like
    // builds drop it (the game's gson is API-compatible for everything AuthCore uses and
    // stays on the compile classpath via Minecraft itself). Jedis also pulls gson in as a
    // TRANSITIVE dependency of the include set, so the whole configuration excludes it.
    if (!(mod.isForge || mod.isNeoforge)) {
        implementation(include("com.google.code.gson:gson:${property("gson_version")}")!!)
    } else {
        configurations.include.get().exclude(group = "com.google.code.gson", module = "gson")
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

// Build + collect
// ----------------------------------------------------------------------------

// Copies the built jar of every variant into the top-level dist/ folder so a single
// `gradlew build` over all versions collects every released artifact in one
// place (used by the release pipeline, the host-test harness and manual deploys).
// The remapped jar is the deployable artifact on intermediary-mapped variants
// (G1/G2 fabric+forge), while the unobfuscated G3 variants only produce a plain jar.
val collectJars = tasks.register<Copy>("collectJars") {
    group = "build"
    val archiveTask = provider<org.gradle.api.tasks.bundling.AbstractArchiveTask> {
        (tasks.findByName("remapJar") ?: tasks.findByName("jar")) as org.gradle.api.tasks.bundling.AbstractArchiveTask
    }
    from(archiveTask.flatMap { it.archiveFile })
    into(rootProject.layout.projectDirectory.dir("dist"))
    dependsOn("build")
}
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:-deprecation")
}

// ----------------------------------------------------------------------------
// Standalone security-test support
// ----------------------------------------------------------------------------
// Exports EVERY compile+runtime dependency jar of this variant into
// test/security-libs/ so test/run-security-tests.sh can compile and
// run against the exact classpath the mod was built with - WITHOUT depending on the
// developer's global Gradle cache (a CI matrix leg only resolves this one variant, so
// standalone copies of e.g. slf4j-api/commons-lang3 may simply not be cached there).
val exportSecurityTestLibs = tasks.register<Copy>("exportSecurityTestLibs") {
    group = "verification"
    description = "Exports the variant dependency jars consumed by test/run-security-tests.sh."
    into(rootProject.layout.projectDirectory.dir("test/security-libs"))
    from(configurations.getByName("compileClasspath"))
    from(configurations.getByName("runtimeClasspath"))
}

// ----------------------------------------------------------------------------
// Aggregate tasks (run on EVERY variant at once).
//
//   buildAll          - compiles all seven variants (3 ranges x fabric/forge/neoforge)
//                       and stages the release jars into dist/ - the one command IDEs,
//                       CI and release scripts use. Usable as `gradlew buildAll`.
//   testAll           - buildAll + the standalone security/migration test suite
//                       (test/run-security-tests.sh) - full local verification.
//   dockerTest        - buildAll + the Docker host-test harness (test/docker/run-tests.sh)
//                       which boots REAL servers in parallel on the official
//                       eclipse-temurin JRE images and verifies the mod on every
//                       released range. Requires Docker.
// ----------------------------------------------------------------------------
val variantProjects = stonecutter.versions.map { it.project }

tasks.register("buildAll") {
    group = "build"
    description = "Builds every Stonecutter variant (all loaders x all ranges) and collects the jars into dist/."
    dependsOn(variantProjects.map { ":$it:build" })
    dependsOn("collectJars")
    dependsOn(":1.21.11-fabric:exportSecurityTestLibs")
}

tasks.register("testAll") {
    group = "verification"
    description = "buildAll + security & migration tests against the built classes."
    dependsOn("buildAll")
    finalizedBy("runSecurityTests")
}

tasks.register("dockerTest") {
    group = "verification"
    description = "buildAll + Docker host tests (real servers, parallel, eclipse-temurin images)."
    dependsOn("buildAll")
    finalizedBy("runDockerTests")
}

tasks.register<Exec>("runSecurityTests") {
    group = "verification"
    description = "Runs test/run-security-tests.sh (compiled against the built variant classes)."
    workingDir(rootProject.layout.projectDirectory)
    val bash = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "bash" else "bash"
    commandLine(bash, "test/run-security-tests.sh")
}

tasks.register<Exec>("runDockerTests") {
    group = "verification"
    description = "Runs test/docker/run-tests.sh --smoke (boots real servers in parallel in Docker)."
    workingDir(rootProject.layout.projectDirectory)
    commandLine("bash", "test/docker/run-tests.sh", "--smoke")
}

