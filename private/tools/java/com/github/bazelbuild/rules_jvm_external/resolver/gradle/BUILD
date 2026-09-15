load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_jvm_external//:defs.bzl", "artifact")

java_library(
    name = "gradle",
    srcs = glob(
        ["*.java"],
        exclude = ["GradleMain.java"],
    ),
    data = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/gradle/data:gradle_build_templates",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/gradle/plugin:plugin.jar",
        "@bazel_tools//tools/jdk:host_jdk",
        "@gradle//:distribution",
        "@gradle//:gradle-bin/README",
    ],
    visibility = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver:__subpackages__",
        "//tests/com/github/bazelbuild/rules_jvm_external:__subpackages__",
    ],
    deps = [
        "//resolver:resolver",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/events",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/gradle/models",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/netrc",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/remote/gcs:gcs_download_service",
        artifact(
            "org.gradle:gradle-tooling-api",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "org.apache.maven:maven-model",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "org.codehaus.plexus:plexus-utils",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "org.codehaus.plexus:plexus-xml",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "com.github.jknack:handlebars",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "com.google.code.gson:gson",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "com.google.guava:guava",
            repository_name = "rules_jvm_external_deps",
        ),
        "@bazel_tools//tools/java/runfiles",
        "@gradle",
    ],
)

java_library(
    name = "gradle-resolver-lib",
    srcs = [
        "GradleMain.java",
    ],
    visibility = ["//visibility:public"],
    deps = [
        ":gradle",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/cmd",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/events",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/netrc",
    ],
)
