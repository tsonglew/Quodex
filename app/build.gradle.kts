import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.tsonglew.quodex"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tsonglew.quodex"
        minSdk = 29
        targetSdk = 36
        versionCode = 26
        versionName = "0.1.0-alpha26"
        resourceConfigurations += listOf("en", "zh", "ja")
        testInstrumentationRunner = "com.tsonglew.quodex.QuotaTestRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// These native leaf layouts work around an OEM host's Button rendering and
// clickable-wrapper handling. Review the override whenever Glance changes.
val verifyGlanceButtonLayouts by tasks.registering {
    group = "verification"
    description = "Verify widget leaf overrides against the resolved Glance layouts"
    doLast {
        val artifacts = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").map { configuration ->
            configurations.getByName(configuration).resolvedConfiguration.resolvedArtifacts.single {
                it.moduleVersion.id.group == "androidx.glance" && it.name == "glance-appwidget"
            }
        }
        check(artifacts.all { it.moduleVersion.id.version == "1.1.1" }) {
            "Review widget leaf overrides before changing Glance 1.1.1"
        }
        check(artifacts.map { it.file }.distinct().size == 1) {
            "Debug and release must use the same reviewed Glance widget layouts"
        }
        val artifact = artifacts.first()
        val pattern = Regex("res/layout[^/]*/glance_button(?:_[^/]+)?\\.xml")
        val resourceRoot = file("src/main/res")
        val overrides = resourceRoot.walkTopDown().filter { it.isFile }
            .map { "res/" + it.relativeTo(resourceRoot).invariantSeparatorsPath }
            .filter { pattern.matches(it) }.toSet()
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        fun attributes(element: Element): Map<String, String> =
            (0 until element.attributes.length).associate { index ->
                element.attributes.item(index).let { it.nodeName to it.nodeValue }
            }
        val leafAttributes = mapOf(
            "android:gravity" to "center", "android:padding" to "0dp",
            "android:clickable" to "true", "android:focusable" to "true",
            "android:importantForAccessibility" to "yes",
            "android:background" to "@android:color/transparent",
        )
        ZipFile(artifact.file).use { archive ->
            val layouts = archive.entries().asSequence().map { it.name }
                .filter { pattern.matches(it) }.toSet()
            check(layouts.size == 12 && overrides == layouts) {
                "Glance widget layout coverage changed: missing=${layouts - overrides}, extra=${overrides - layouts}"
            }
            layouts.forEach { name ->
                val original = archive.getInputStream(archive.getEntry(name)).use {
                    parser.parse(it).documentElement
                }
                val replacement = parser.parse(file("src/main/$name")).documentElement
                check(original.tagName == "Button" && replacement.tagName == "TextView") {
                    "Unexpected widget leaf type in $name"
                }
                check(attributes(replacement) == attributes(original) - "style" + leafAttributes) {
                    "Widget leaf override must preserve Glance layout metadata: $name"
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyGlanceButtonLayouts) }
