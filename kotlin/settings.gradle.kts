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

include("android:common")
include("android:main")
include("android:server:core")
include("android:server:injected")
include("android:server:sdk")
include("android:plugin-sdk")
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
include("internal:tool:d8-preserve-manifest")
include("internal:tool:prepare-release")
include("internal:tool:version-code")
