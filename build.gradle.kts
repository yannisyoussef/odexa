plugins {
    base
}

subprojects {
    plugins.withId("java") {
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation", "-Werror"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform { excludeTags("integration") }
            testLogging { events("failed", "skipped") }
            doFirst {
                classpath.files.firstOrNull { it.name.startsWith("mockito-core-") }?.let {
                    jvmArgs("-javaagent:${it.absolutePath}")
                }
            }
        }
        val integrationTest by tasks.registering(Test::class) {
            description = "Runs infrastructure integration tests (Docker required)."
            group = "verification"
            val sourceSets = project.extensions.getByType<SourceSetContainer>()
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].runtimeClasspath
            useJUnitPlatform {
                excludeTags.clear()
                includeTags("integration")
            }
            shouldRunAfter(tasks.named("test"))
        }
        dependencyLocking { lockAllConfigurations() }
    }
}
