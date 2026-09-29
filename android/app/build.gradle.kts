plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.itantra.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.itantra.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/modelAssets"))
    sourceSets["test"].resources.srcDir("../../link")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val syncModelMetadata by tasks.registering(Copy::class) {
    from("../../p2-models") { include("manifest.json", "alerts.json") }
    into(layout.buildDirectory.dir("generated/modelAssets"))
}
tasks.named("preBuild") { dependsOn(syncModelMetadata) }
