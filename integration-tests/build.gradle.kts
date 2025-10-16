import java.io.ByteArrayOutputStream

plugins {
    base
}

// Configuration for test API levels
data class ApiConfig(
    val apiLevel: Int,
    val androidVersion: String
)

val apiLevels = listOf(
    ApiConfig(29, "10.0"),
    ApiConfig(30, "11.0"),
    ApiConfig(31, "12.0"),
    ApiConfig(33, "13.0"),
    ApiConfig(34, "14.0")
)

// Create test tasks for each API level
apiLevels.forEach { config ->
    val capitalizedName = "Api${config.apiLevel}"

    tasks.register<Exec>("test${capitalizedName}") {
        group = "verification"
        description = "Run Stoic tests on Android ${config.androidVersion} (API ${config.apiLevel})"

        workingDir = rootProject.projectDir.resolve("test")
        commandLine(
            "./with-emulator.sh",
            config.apiLevel.toString(),
            "./run-all-tests-on-connected-device.sh"
        )

        doFirst {
            println("Running tests on API ${config.apiLevel} using with-emulator.sh...")
        }
    }
}

// Convenience task to run all tests sequentially
tasks.register("testAll") {
    group = "verification"
    description = "Run tests on all API levels (sequentially)"

    apiLevels.forEach { config ->
        dependsOn("testApi${config.apiLevel}")
    }

    // Make tests run sequentially, not in parallel
    tasks.findByName("testApi30")?.mustRunAfter("testApi29")
    tasks.findByName("testApi31")?.mustRunAfter("testApi30")
    tasks.findByName("testApi33")?.mustRunAfter("testApi31")
    tasks.findByName("testApi34")?.mustRunAfter("testApi33")
}
