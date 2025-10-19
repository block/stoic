rootProject.name = "stoic"

pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

include("target:common")
include("target:runtime:core")
include("target:runtime:attached")
include("target:runtime:app-sdk")
include("target:plugin-sdk")
include("generated-bridge")
include("common")
include("demo-app:without-sdk")
include("demo-app:with-sdk")
include("demo-plugin:appexitinfo")
include("demo-plugin:breakpoint")
include("demo-plugin:helloworld")
include("demo-plugin:crasher")
include("test-plugin")
include("host:main")
include("internal:versioning")
include("internal:tool:jar-to-apk-preserve-manifest")
include("internal:tool:release")
include("internal:tool:version-code")
include("native")
include("integration-tests")
