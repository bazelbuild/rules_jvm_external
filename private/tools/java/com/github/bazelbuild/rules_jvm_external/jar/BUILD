load("@rules_jvm_external//:defs.bzl", "artifact")

java_binary(
    name = "AddJarManifestEntry",
    srcs = ["AddJarManifestEntry.java"],
    main_class = "com.github.bazelbuild.rules_jvm_external.jar.AddJarManifestEntry",
    visibility = [
        "//visibility:public",
    ],
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/zip",
    ],
)

java_library(
    name = "ListPackages-lib",
    srcs = ["ListPackages.java"],
    visibility = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external:__subpackages__",
        "//tests/com/github/bazelbuild/rules_jvm_external/jar:__pkg__",
    ],
    deps = [
        artifact(
            "com.google.code.gson:gson",
            repository_name = "rules_jvm_external_deps",
        ),
    ],
)

java_binary(
    name = "ListPackages",
    main_class = "com.github.bazelbuild.rules_jvm_external.jar.ListPackages",
    visibility = ["//visibility:public"],
    runtime_deps = [
        ":ListPackages-lib",
    ],
)

java_binary(
    name = "MergeJars",
    srcs = [
        "DuplicateEntryStrategy.java",
        "MergeJars.java",
    ],
    main_class = "com.github.bazelbuild.rules_jvm_external.jar.MergeJars",
    visibility = [
        "//visibility:public",
    ],
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/zip",
    ],
)
