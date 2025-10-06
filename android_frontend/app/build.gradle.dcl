androidApplication {
    namespace = "org.example.app"

    dependencies {
        implementation("org.apache.commons:commons-text:1.11.0")
        implementation(project(":utilities"))
        // AndroidX Core for FileProvider and modern core utilities
        implementation("androidx.core:core-ktx:1.13.1")

        testing {
            dependencies {
                implementation("junit:junit:4.13.2")
            }
        }
    }
}
