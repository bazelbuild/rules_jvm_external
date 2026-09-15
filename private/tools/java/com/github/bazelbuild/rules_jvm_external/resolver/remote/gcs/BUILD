load("@rules_java//java:java_library.bzl", "java_library")
load("//private/rules:artifact.bzl", "artifact")

java_library(
    name = "gcs_download_service",
    srcs = [
        "GcsDownloadService.java",
        "GcpTokenProvider.java",
    ],
    resources = ["META-INF/services/com.github.bazelbuild.rules_jvm_external.resolver.DownloadService"],
    resource_strip_prefix = package_name(),
    visibility = ["//visibility:public"],
    deps = [
        "//resolver:resolver",
        artifact(
            "com.google.auth:google-auth-library-oauth2-http",
            repository_name = "rules_jvm_external_deps",
        ),
        artifact(
            "com.google.cloud:google-cloud-storage",
            repository_name = "rules_jvm_external_deps",
        ),
    ],
)
