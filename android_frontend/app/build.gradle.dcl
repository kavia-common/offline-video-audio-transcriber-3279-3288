androidApplication {
    namespace = "org.example.app"

    dependencies {
        implementation("org.apache.commons:commons-text:1.11.0")
        implementation(project(":utilities"))

        // AndroidX AppCompat for AppCompatActivity
        implementation("androidx.appcompat:appcompat:1.7.0")

        // AndroidX Core for FileProvider and modern core utilities
        implementation("androidx.core:core-ktx:1.13.1")

        // WorkManager for background and foreground processing (compatible with compileSdk 34)
        implementation("androidx.work:work-runtime-ktx:2.9.1")

        // Kotlin Coroutines for asynchronous tasks on Android
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

        // RecyclerView for future status adapter (optional but added to enable upcoming steps)
        implementation("androidx.recyclerview:recyclerview:1.3.2")

        // Material Components
        implementation("com.google.android.material:material:1.12.0")

        // Vosk offline speech-to-text engine (Android bindings from AlphaCephei Maven)
        implementation("com.alphacephei:vosk-android:0.3.47")

        testing {
            dependencies {
                implementation("junit:junit:4.13.2")
            }
        }
    }
}
