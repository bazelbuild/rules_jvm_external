load("@rules_java//java:java_test.bzl", "java_test")
load("//:defs.bzl", "artifact")

java_test(
    name = "GcsDownloadServiceTest",
    size = "small",
    srcs = ["GcsDownloadServiceTest.java"],
    test_class = "com.github.bazelbuild.rules_jvm_external.resolver.remote.gcs.GcsDownloadServiceTest",
    deps = [
        "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/remote/gcs:gcs_download_service",
        "//resolver:resolver",
        artifact(
            "com.google.cloud:google-cloud-storage",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "junit:junit",
            repository_name = "regression_testing_coursier",
        ),
        artifact(
            "org.mockito:mockito-core",
            repository_name = "regression_testing_coursier",
        ),
    ],
)
