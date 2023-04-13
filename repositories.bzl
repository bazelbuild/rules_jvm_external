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

    http_archive(
        name = "gradle",
        build_file = "@rules_jvm_external//:gradle.BUILD.bazel",
        sha256 = "7ba68c54029790ab444b39d7e293d3236b2632631fb5f2e012bb28b4ff669e4b",
        url = "https://services.gradle.org/distributions/gradle-7.6-bin.zip",
    )

    maven_install(
        name = "rules_jvm_external_deps",
        artifacts = [
            "com.esotericsoftware.kryo:kryo:2.24.0",
            "com.google.auth:google-auth-library-credentials:1.13.0",
            "com.google.auth:google-auth-library-oauth2-http:1.13.0",
            "com.google.cloud:google-cloud-core:2.9.0",
            "com.google.cloud:google-cloud-storage:2.16.0",
            "com.google.code.gson:gson:2.10",
            "com.google.googlejavaformat:google-java-format:1.15.0",
            "com.google.guava:guava:31.1-jre",
            "commons-io:commons-io:2.11.0",
            "commons-lang:commons-lang:2.6",
            "org.apache.ant:ant:1.10.12",
            "org.apache.commons:commons-compress:1.22",
            "org.apache.ivy:ivy:2.5.1",
            "org.apache.maven:maven-artifact:3.8.6",
            "org.apache.maven:maven-core:3.8.6",
            "org.apache.maven:maven-model:3.8.6",
            "org.apache.maven:maven-resolver-provider:3.8.6",
            "org.apache.maven:maven-settings-builder:3.8.6",
            "org.apache.maven:maven-settings:3.8.6",
            "org.apache.maven.resolver:maven-resolver-api:1.9.2",
            "org.apache.maven.resolver:maven-resolver-connector-basic:1.9.2",
            "org.apache.maven.resolver:maven-resolver-impl:1.9.2",
            "org.apache.maven.resolver:maven-resolver-spi:1.9.2",
            "org.apache.maven.resolver:maven-resolver-transport-file:1.9.2",
            "org.apache.maven.resolver:maven-resolver-transport-http:1.9.2",
            "org.apache.maven.resolver:maven-resolver-util:1.9.2",
            "org.assertj:assertj-core:3.23.1",
            "org.codehaus.groovy:groovy-ant:3.0.13",
            "org.codehaus.groovy:groovy-json:3.0.13",
            "org.codehaus.plexus:plexus-cipher:2.0",
            "org.codehaus.plexus:plexus-sec-dispatcher:2.0",
            "org.fusesource.jansi:jansi:2.4.0",
            "org.jetbrains.kotlin:kotlin-daemon-embeddable:1.7.22",
            "org.jetbrains.kotlin:kotlin-stdlib-common:1.7.22",
            "org.jetbrains.kotlin:kotlin-stdlib:1.7.10",
            "org.junit.jupiter:junit-jupiter-api:5.9.1",
            "org.junit.jupiter:junit-jupiter-engine:5.9.1",
            "org.junit.platform:junit-platform-launcher:1.9.1",
            "org.junit.platform:junit-platform-reporting:1.9.1",
            "org.junit.vintage:junit-vintage-engine:5.9.1",
            "org.ow2.asm:asm:9.4",
            "org.slf4j:jul-to-slf4j:2.0.5",
            "org.slf4j:log4j-over-slf4j:2.0.5",
            "org.slf4j:slf4j-simple:2.0.5",
            "software.amazon.awssdk:s3:2.18.35",
        ],
        maven_install_json = "@rules_jvm_external//:rules_jvm_external_deps_install.json",
        fail_if_repin_required = True,
        strict_visibility = True,
        repositories = repositories,
    )
