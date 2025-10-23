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

include("target:jvmti-attach")

include("target:app-sdk")

include("target:plugin-sdk")

include("protocol")

include("demo-app:without-sdk")

include("demo-app:with-sdk")

include("demo-plugin:appexitinfo")

include("demo-plugin:breakpoint")

include("demo-plugin:helloworld")

include("demo-plugin:crasher")

include("test-plugin")

include("host:main")

include("internal:tool:jar-to-apk-preserve-manifest")

include("internal:tool:release")

include("internal:test:protocol-version-client")

include("native")

include("integration-tests")
