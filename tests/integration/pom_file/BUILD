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
        "@maven//:io_netty_netty_tcnative_boringssl_static_linux_aarch_64",
    ],
    deps = [
        "@maven//:io_netty_netty_tcnative_boringssl_static_linux_aarch_64",
        "@maven//:io_netty_netty_tcnative_boringssl_static_linux_x86_64",
    ],
)

diff_test(
    name = "validate-pom-example-with-classifier-dep",
    file1 = ":pom-example-with-classifier-dep-pom",
    file2 = "pom-with-classifier-dep.golden.xml",
)
