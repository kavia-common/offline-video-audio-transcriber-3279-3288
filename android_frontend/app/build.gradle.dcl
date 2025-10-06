androidApplication {
    namespace = "org.example.app"

    dependencies {
        implementation("org.apache.commons:commons-text:1.11.0")
        implementation(project(":utilities"))

        testing {
            dependencies {
                implementation("junit:junit:4.13.2")
            }
        }
    }
}
