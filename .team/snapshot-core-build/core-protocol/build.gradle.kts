plugins { `java-library` }
group = "dev.agentmirror.core"
version = "20260915.close"
dependencies {
    api(files(rootProject.providers.gradleProperty("goldenCoreRepo").get() + "/dev/agentmirror/core/core-protocol/20260915.close/core-protocol-20260915.close.jar"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
