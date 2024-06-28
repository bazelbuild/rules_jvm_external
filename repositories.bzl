load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")

# Minimise the risk of accidentally depending on something that's not already loaded
load("//private/rules:maven_install.bzl", "maven_install")

_DEFAULT_REPOSITORIES = [
    "https://repo1.maven.org/maven2",
]

_MAVEN_VERSION = "3.9.6"
_MAVEN_RESOLVER_VERSION = "1.9.18"

def rules_jvm_external_deps(
        repositories = _DEFAULT_REPOSITORIES,
        deps_lock_file = "@rules_jvm_external//:rules_jvm_external_deps_install.json"):
    maybe(
        http_archive,
        name = "bazel_skylib",
        sha256 = "66ffd9315665bfaafc96b52278f57c7e2dd09f5ede279ea6d39b2be471e7e3aa",
        urls = [
            "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/1.4.2/bazel-skylib-1.4.2.tar.gz",
            "https://github.com/bazelbuild/bazel-skylib/releases/download/1.4.2/bazel-skylib-1.4.2.tar.gz",
        ],
    )

    # The `rules_java` major version is tied to the major version of Bazel that it supports,
    # so this is different from the version in the MODULE file.
    major_version = native.bazel_version.partition(".")[0]
    if major_version == "5":
        maybe(
            http_archive,
            name = "rules_java",
            urls = [
                "https://github.com/bazelbuild/rules_java/releases/download/5.5.1/rules_java-5.5.1.tar.gz",
            ],
            sha256 = "73b88f34dc251bce7bc6c472eb386a6c2b312ed5b473c81fe46855c248f792e0",
        )

    elif major_version == "6":
        maybe(
            http_archive,
            name = "rules_java",
            urls = [
                "https://github.com/bazelbuild/rules_java/releases/download/6.5.2/rules_java-6.5.2.tar.gz",
            ],
            sha256 = "16bc94b1a3c64f2c36ceecddc9e09a643e80937076b97e934b96a8f715ed1eaa",
        )

    else:
        maybe(
            http_archive,
            name = "rules_java",
            urls = [
                "https://github.com/bazelbuild/rules_java/releases/download/7.3.2/rules_java-7.3.2.tar.gz",
            ],
            sha256 = "3121a00588b1581bd7c1f9b550599629e5adcc11ba9c65f482bbd5cfe47fdf30",
        )

    maven_install(
        name = "rules_jvm_external_deps",
        artifacts = [
            "com.google.auth:google-auth-library-credentials:1.23.0",
            "com.google.auth:google-auth-library-oauth2-http:1.23.0",
            "com.google.cloud:google-cloud-core:2.39.0",
            "com.google.cloud:google-cloud-storage:2.40.0",
            "com.google.code.gson:gson:2.11.0",
            "com.google.googlejavaformat:google-java-format:1.22.0",
            "com.google.guava:guava:33.2.1-jre",
            "org.apache.maven:maven-artifact:%s" % _MAVEN_VERSION,
            "org.apache.maven:maven-core:%s" % _MAVEN_VERSION,
            "org.apache.maven:maven-model:%s" % _MAVEN_VERSION,
            "org.apache.maven:maven-model-builder:%s" % _MAVEN_VERSION,
            "org.apache.maven:maven-settings:%s" % _MAVEN_VERSION,
            "org.apache.maven:maven-settings-builder:%s" % _MAVEN_VERSION,
            "org.apache.maven:maven-resolver-provider:%s" % _MAVEN_VERSION,
            "org.apache.maven.resolver:maven-resolver-api:%s" % _MAVEN_RESOLVER_VERSION,
            "org.apache.maven.resolver:maven-resolver-impl:%s" % _MAVEN_RESOLVER_VERSION,
            "org.apache.maven.resolver:maven-resolver-connector-basic:%s" % _MAVEN_RESOLVER_VERSION,
            "org.apache.maven.resolver:maven-resolver-spi:%s" % _MAVEN_RESOLVER_VERSION,
            "org.apache.maven.resolver:maven-resolver-transport-file:%s" % _MAVEN_RESOLVER_VERSION,
            "org.apache.maven.resolver:maven-resolver-transport-http:%s" % _MAVEN_RESOLVER_VERSION,
            "org.apache.maven.resolver:maven-resolver-util:%s" % _MAVEN_RESOLVER_VERSION,
            "org.codehaus.plexus:plexus-cipher:2.1.0",
            "org.codehaus.plexus:plexus-sec-dispatcher:2.0",
            "org.codehaus.plexus:plexus-utils:3.5.1",
            "org.fusesource.jansi:jansi:2.4.1",
            "org.slf4j:jul-to-slf4j:2.0.13",
            "org.slf4j:log4j-over-slf4j:2.0.13",
            "org.slf4j:slf4j-simple:2.0.13",
            "software.amazon.awssdk:s3:2.26.5",
        ],
        maven_install_json = deps_lock_file,
        fail_if_repin_required = True,
        strict_visibility = True,
        fetch_sources = True,
        repositories = repositories,
    )
