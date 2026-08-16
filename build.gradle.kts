import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        pluginVerifier()
        zipSigner()
        // Platform test framework → BasePlatformTestCase for the CommentInlayManager UI-lifecycle test.
        testFramework(TestFrameworkType.Platform)
    }

    // Bundled with the plugin: the IntelliJ Platform does not expose Gson on the plugin classpath,
    // so we ship our own. Drives the byte-exact snake_case comment JSON contract (plan.html §5).
    // error_prone_annotations is compile-only (CLASS retention) — exclude it so we don't bundle it.
    implementation("com.google.code.gson:gson:2.11.0") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // BasePlatformTestCase extends junit.framework.TestCase (JUnit3/4): the JUnit4 jar is needed at
    // compile time, and the vintage engine runs it under useJUnitPlatform() beside the Jupiter tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Pure-Kotlin plugin: no Java @NotNull / GUI-form instrumentation is needed, and no
    // searchable-options index is required for Phase 0. Disabling both keeps builds lean
    // and sidesteps the instrumentCode JDK-compiler path issue.
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound — heview targets all current & future JetBrains IDEs.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
