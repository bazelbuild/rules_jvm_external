load("//:defs.bzl", "artifact")

java_test(
    name = "VersionTest",
    srcs = ["VersionTest.java"],
    test_class = "com.github.bazelbuild.rules_jvm_external.javadoc.VersionTest",
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/javadoc",
        artifact(
            "junit:junit",
            repository_name = "regression_testing_coursier",
        ),
        artifact("org.hamcrest:hamcrest"),
        artifact("org.hamcrest:hamcrest-core"),
    ],
)

java_test(
    name = "ResourceTest",
    srcs = ["ResourceTest.java"],
    test_class = "com.github.bazelbuild.rules_jvm_external.javadoc.ResourceTest",
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external",
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/javadoc",
        "//tests/com/github/bazelbuild/rules_jvm_external:JarUtils",
        artifact(
            "junit:junit",
            repository_name = "regression_testing_coursier",
        ),
        artifact("com.google.guava:guava"),
    ],
)
