load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")

# Minimise the risk of accidentally depending on something that's not already loaded
load("//private/rules:maven_install.bzl", "maven_install")

_DEFAULT_REPOSITORIES = [
    "https://repo1.maven.org/maven2",
    "https://maven.google.com",
]

def rules_jvm_external_deps(repositories = _DEFAULT_REPOSITORIES):
    maybe(
        http_archive,
        name = "bazel_skylib",
        sha256 = "b8a1527901774180afc798aeb28c4634bdccf19c4d98e7bdd1ce79d1fe9aaad7",
        urls = [
            "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz",
            "https://github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz",
        ],
    )

    maven_install(
        name = "rules_jvm_external_deps",
        artifacts = [
            "com.google.guava:guava:31.1-jre",
            "com.google.auth:google-auth-library-credentials:0.22.0",
            "com.google.auth:google-auth-library-oauth2-http:0.22.0",
            "com.google.cloud:google-cloud-core:1.93.10",
            "com.google.cloud:google-cloud-storage:1.113.4",
            "com.google.code.gson:gson:2.9.0",
            "com.google.googlejavaformat:google-java-format:1.15.0",
            "com.google.guava:guava:31.1-jre",
            "org.apache.maven:maven-artifact:3.8.6",
            "software.amazon.awssdk:s3:2.17.183",
        ],
        maven_install_json = "@rules_jvm_external//:rules_jvm_external_deps_install.json",
        fail_if_repin_required = True,
        strict_visibility = True,
        repositories = repositories,
    )
