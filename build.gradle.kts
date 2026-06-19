plugins {
    // Create a shadow/fat jar that bundles non-core dependencies (gson).
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin (from the settings plugin).
    id("qupath-conventions")
}

// Configure the extension
qupathExtension {
    name = "qupath-extension-tme-quant"
    group = "io.github.uw-loci"
    version = "0.1.0"
    description = "TME Quant: sends QuPath image regions to the TMEQuant FIRE fiber pipeline over a socket and draws the returned fibers as annotations/detections (tiling, seam-stitching, TACS)."
    automaticModule = "io.github.uw.loci.extension.tmequant"
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            name = "OME-Artifacts"
            url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
        }
    }
}

dependencies {
    // Provided by QuPath at runtime.
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    // gson is used to (de)serialise the socket JSON. Bundle it so the jar is
    // self-contained even if a given QuPath build does not ship it.
    shadow(libs.gson)
}

tasks.withType<JavaCompile> {
    // QuPath 0.7 runs on Java 21; pin bytecode target so any build JDK emits
    // loadable classes.
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

// QuPath 0.7.0's maven artifacts are published as requiring JVM 25 (org.gradle.jvm.version=25),
// even though the QuPath app runs on Java 21. options.release=21 makes Gradle resolve a
// JVM-21-compatible classpath, which then rejects those JVM-25 artifacts on a clean build. Force
// the resolvable classpaths to request JVM 25 so the deps resolve; bytecode target (21) is
// unaffected, so the jar still loads on Java 21. (Upstream QuPath metadata bug; remove if fixed.)
configurations.configureEach {
    if (isCanBeResolved) {
        attributes {
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}
