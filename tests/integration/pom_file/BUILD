load("@bazel_skylib//rules:diff_test.bzl", "diff_test")
load("//:defs.bzl", "artifact", "java_export", "maven_bom")

java_export(
    name = "pom-example",
    srcs = ["PomExample.java"],
    maven_coordinates = "com.example:app:1.0.0",
    deps = [
        "//tests/integration/maven_bom:one-dep",
    ],
)

diff_test(
    name = "validate-pom",
    file1 = ":pom-example-pom",
    file2 = "pom.golden.xml",
)

java_export(
    name = "pom-example-with-runtime-dep",
    srcs = ["PomExample.java"],
    maven_coordinates = "com.example:app:1.0.0",
    runtime_deps = [
        "//tests/integration/maven_bom:one-dep",
    ],
    deps = [
        "//tests/integration/maven_bom:one-dep",
        artifact("com.google.guava:guava"),
    ],
)

diff_test(
    name = "validate-pom-with-runtime-dep",
    file1 = ":pom-example-with-runtime-dep-pom",
    file2 = "pom-with-runtime-dep.golden.xml",
)

java_export(
    name = "pom-example-with-classifier-dep",
    srcs = ["PomExample.java"],
    maven_coordinates = "com.example:app:1.0.0",
    runtime_deps = [
        ":classifier-artifact-one",
    ],
    deps = [
        ":classifier-artifact-one",
        ":classifier-artifact-two",
    ],
)

java_export(
    name = "classifier-artifact-one",
    srcs = ["ClassifierArtifactOne.java"],
    maven_coordinates = "com.example:lib:jar:linux-x86:1.0.0",
)

java_export(
    name = "classifier-artifact-two",
    srcs = ["ClassifierArtifactTwo.java"],
    maven_coordinates = "com.example:lib:jar:linux-arm64:1.0.0",
)

diff_test(
    name = "validate-pom-example-with-classifier-dep",
    file1 = ":pom-example-with-classifier-dep-pom",
    file2 = "pom-with-classifier-dep.golden.xml",
)
