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
include("target:runtime:sdk")
include("target:plugin-sdk")
include("bridge")
include("common")
include("demo-app:without-sdk")
include("demo-app:with-sdk")
include("demo-plugin:appexitinfo")
include("demo-plugin:breakpoint")
include("demo-plugin:helloworld")
include("demo-plugin:crasher")
include("demo-plugin:testsuite")
include("host:main")
include("internal:versioning")
include("internal:tool:jar-to-apk-preserve-manifest")
include("internal:tool:prepare-release")
include("internal:tool:version-code")
include("native")
