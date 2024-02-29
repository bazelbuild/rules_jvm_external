load("//:defs.bzl", "artifact")

java_test(
    name = "AddJarManifestEntryTest",
    srcs = ["AddJarManifestEntryTest.java"],
    test_class = "com.github.bazelbuild.rules_jvm_external.jar.AddJarManifestEntryTest",
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/jar:AddJarManifestEntry",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/zip",
        artifact("com.google.guava:guava"),
        artifact(
            "junit:junit",
            repository_name = "regression_testing",
        ),
    ],
)

java_test(
    name = "IndexJarTest",
    srcs = ["IndexJarTest.java"],
    data = [
        "@google_api_services_compute_javadoc_for_test//file",
        "@gson_for_test//file",
        "@hamcrest_core_for_test//file",
        "@hamcrest_core_srcs_for_test//file",
        "@junit_platform_commons_for_test//file",
        "@lombok_for_test//file",
        "//tests/custom_maven_install:service_indexing_testing.json",
    ],
    test_class = "com.github.bazelbuild.rules_jvm_external.jar.IndexJarTest",
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/jar:IndexJar-lib",
        "@bazel_tools//tools/java/runfiles",
        "@rules_jvm_external_deps//:com_google_code_gson_gson",
        artifact(
            "junit:junit",
            repository_name = "regression_testing",
        ),
        artifact("org.hamcrest:hamcrest"),
    ],
)

java_test(
    name = "MergeJarsTest",
    srcs = ["MergeJarsTest.java"],
    test_class = "com.github.bazelbuild.rules_jvm_external.jar.MergeJarsTest",
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/jar:MergeJars",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/zip",
        artifact("com.google.guava:guava"),
        artifact(
            "junit:junit",
            repository_name = "regression_testing",
        ),
    ],
)
