workspace(name = "rules_jvm_external")

android_sdk_repository(name = "androidsdk")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load("//:repositories.bzl", "rules_jvm_external_deps")

rules_jvm_external_deps()

load("//:setup.bzl", "rules_jvm_external_setup")

rules_jvm_external_setup()

http_archive(
    name = "io_bazel_stardoc",
    sha256 = "3fd8fec4ddec3c670bd810904e2e33170bedfe12f90adf943508184be458c8bb",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/stardoc/releases/download/0.5.3/stardoc-0.5.3.tar.gz",
        "https://github.com/bazelbuild/stardoc/releases/download/0.5.3/stardoc-0.5.3.tar.gz",
    ],
)

load("@io_bazel_stardoc//:setup.bzl", "stardoc_repositories")

stardoc_repositories()

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = "946747acdbeae799b085d12b240ec346f775ac65236dfcf18aa0cd7300f6de78",
    urls = ["https://github.com/bazelbuild/rules_kotlin/releases/download/v1.7.0-RC-2/rules_kotlin_release.tgz"],
)

load("@io_bazel_rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories")

kotlin_repositories()

load("@io_bazel_rules_kotlin//kotlin:core.bzl", "kt_register_toolchains")

kt_register_toolchains()

# Stardoc also depends on skydoc_repositories, rules_sass, rules_nodejs, but our
# usage of Stardoc (scripts/generate_docs) doesn't require any of these
# dependencies. So, we omit them to keep the WORKSPACE file simpler.
# https://skydoc.bazel.build/docs/getting_started_stardoc.html

http_archive(
    name = "build_bazel_rules_nodejs",
    sha256 = "dcc55f810142b6cf46a44d0180a5a7fb923c04a5061e2e8d8eb05ccccc60864b",
    urls = ["https://github.com/bazelbuild/rules_nodejs/releases/download/5.8.0/rules_nodejs-5.8.0.tar.gz"],
)

load("@build_bazel_rules_nodejs//:repositories.bzl", "build_bazel_rules_nodejs_dependencies")

build_bazel_rules_nodejs_dependencies()

load("@build_bazel_rules_nodejs//:index.bzl", "node_repositories", "yarn_install")

node_repositories(
    node_version = "16.17.0",
    yarn_version = "1.22.19",
)

yarn_install(
    name = "npm",
    package_json = "//:package.json",
    yarn_lock = "//:yarn.lock",
)

# Required for buildifier (`//scripts:buildifier`)
http_file(
    name = "buildifier-linux-arm64",
    sha256 = "917d599dbb040e63ae7a7e1adb710d2057811902fdc9e35cce925ebfd966eeb8",
    urls = ["https://github.com/bazelbuild/buildtools/releases/download/5.1.0/buildifier-linux-arm64"],
)

http_file(
    name = "buildifier-linux-x86_64",
    sha256 = "52bf6b102cb4f88464e197caac06d69793fa2b05f5ad50a7e7bf6fbd656648a3",
    urls = ["https://github.com/bazelbuild/buildtools/releases/download/5.1.0/buildifier-linux-amd64"],
)

http_file(
    name = "buildifier-macos-arm64",
    sha256 = "745feb5ea96cb6ff39a76b2821c57591fd70b528325562486d47b5d08900e2e4",
    urls = ["https://github.com/bazelbuild/buildtools/releases/download/5.1.0/buildifier-darwin-arm64"],
)

http_file(
    name = "buildifier-macos-x86_64",
    sha256 = "c9378d9f4293fc38ec54a08fbc74e7a9d28914dae6891334401e59f38f6e65dc",
    urls = ["https://github.com/bazelbuild/buildtools/releases/download/5.1.0/buildifier-darwin-amd64"],
)

# Begin test dependencies

load("//:defs.bzl", "maven_install")
load("//:specs.bzl", "maven")

maven_install(
    artifacts = [
        "com.google.guava:guava:31.1-jre",
        "org.hamcrest:hamcrest-core:2.1",
    ],
    maven_install_json = "@rules_jvm_external//:maven_install.json",
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

load("@maven//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

maven_install(
    name = "exclusion_testing",
    artifacts = [
        maven.artifact(
            artifact = "guava",
            exclusions = [
                maven.exclusion(
                    artifact = "animal-sniffer-annotations",
                    group = "org.codehaus.mojo",
                ),
                "com.google.j2objc:j2objc-annotations",
            ],
            group = "com.google.guava",
            version = "27.0-jre",
        ),
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

maven_install(
    name = "global_exclusion_testing",
    artifacts = [
        "com.google.guava:guava:27.0-jre",  # depends on animal-sniffer-annotations and j2objc-annotations
        "com.squareup.okhttp3:okhttp:3.14.1",  # depends on animal-sniffer-annotations
        "com.diffplug.durian:durian-core:1.2.0",  # depends on animal-sniffer-annotations and j2objc-annotations
    ],
    excluded_artifacts = [
        maven.exclusion(
            artifact = "animal-sniffer-annotations",
            group = "org.codehaus.mojo",
        ),
        "com.google.j2objc:j2objc-annotations",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

maven_install(
    name = "manifest_stamp_testing",
    artifacts = [
        "com.google.guava:guava:27.0-jre",
        "javax.inject:javax.inject:1",
        "org.apache.beam:beam-sdks-java-core:2.15.0",
        "org.bouncycastle:bcprov-jdk15on:1.64",
    ],
    maven_install_json = "//tests/custom_maven_install:manifest_stamp_testing_install.json",
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

load("@manifest_stamp_testing//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

# These artifacts helped discover limitations by the Maven resolver. Each
# artifact listed here *must have* an accompanying issue. We build_test these
# targets to ensure that they remain supported by the rule.
maven_install(
    name = "regression_testing",
    artifacts = [
        # https://github.com/bazelbuild/rules_jvm_external/issues/74
        "org.pantsbuild:jarjar:1.6.6",
        # https://github.com/bazelbuild/rules_jvm_external/issues/59
        "junit:junit:4.12",
        "org.jetbrains.kotlin:kotlin-test:1.3.21",
        # https://github.com/bazelbuild/rules_jvm_external/issues/101
        # As referenced in the issue, daml is not available anymore, hence
        # replacing with another artifact with a classifier.
        "org.eclipse.jetty:jetty-http:jar:tests:9.4.20.v20190813",
        # https://github.com/bazelbuild/rules_jvm_external/issues/116
        "org.eclipse.jetty.orbit:javax.servlet:3.0.0.v201112011016",
        # https://github.com/bazelbuild/rules_jvm_external/issues/92#issuecomment-478430167
        maven.artifact(
            "com.squareup",
            "javapoet",
            "1.11.1",
            neverlink = True,
        ),
        # https://github.com/bazelbuild/rules_jvm_external/issues/98
        "com.github.fommil.netlib:all:1.1.2",
        "nz.ac.waikato.cms.weka:weka-stable:3.8.1",
        # https://github.com/bazelbuild/rules_jvm_external/issues/111
        "com.android.support:appcompat-v7:aar:28.0.0",
        "com.google.android.gms:play-services-base:16.1.0",
        # https://github.com/bazelbuild/rules_jvm_external/issues/119#issuecomment-484278260
        "org.apache.flink:flink-test-utils_2.12:1.8.0",
        # https://github.com/bazelbuild/rules_jvm_external/issues/170
        "ch.epfl.scala:compiler-interface:1.3.0-M4+20-c8a2f9bd",
        # https://github.com/bazelbuild/rules_jvm_external/issues/172
        "org.openjfx:javafx-base:11.0.1",
        # https://github.com/bazelbuild/rules_jvm_external/issues/178
        "io.kubernetes:client-java:4.0.0-beta1",
        # https://github.com/bazelbuild/rules_jvm_external/issues/199
        "com.google.ar.sceneform.ux:sceneform-ux:1.10.0",
        # https://github.com/bazelbuild/rules_jvm_external/issues/119#issuecomment-504704752
        "com.github.oshi:oshi-parent:3.4.0",
        "com.github.spinalhdl:spinalhdl-core_2.11:1.3.6",
        "com.github.spinalhdl:spinalhdl-lib_2.11:1.3.6",
        # https://github.com/bazelbuild/rules_jvm_external/issues/201
        "org.apache.kafka:kafka_2.11:2.1.1",
        "io.confluent:kafka-avro-serializer:5.0.1",
        # https://github.com/bazelbuild/rules_jvm_external/issues/309
        "io.quarkus.http:quarkus-http-servlet:3.0.0.Beta1",
        # https://github.com/bazelbuild/rules_jvm_external/issues/371
        "com.fasterxml.jackson:jackson-bom:2.9.10",
        "org.junit:junit-bom:5.3.1",
        # https://github.com/bazelbuild/rules_jvm_external/issues/686
        "io.netty:netty-tcnative-boringssl-static:2.0.51.Final",
        # https://github.com/bazelbuild/rules_jvm_external/issues/852
        maven.artifact(
            artifact = "jaxb-ri",
            exclusions = [
                "com.sun.xml.bind:jaxb-samples",
                "com.sun.xml.bind:jaxb-release-documentation",
            ],
            group = "com.sun.xml.bind",
            version = "2.3.6",
        ),
        # https://github.com/bazelbuild/rules_jvm_external/issues/865
        maven.artifact(
            artifact = "google-api-services-compute",
            classifier = "javadoc",
            group = "com.google.apis",
            version = "v1-rev235-1.25.0",
        ),
    ],
    generate_compat_repositories = True,
    maven_install_json = "//tests/custom_maven_install:regression_testing_install.json",
    override_targets = {
        "com.google.ar.sceneform:rendering": "@//tests/integration/override_targets:sceneform_rendering",
    },
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
        "https://packages.confluent.io/maven/",
    ],
)

# Grab com.google.ar.sceneform:rendering because we overrode it above
http_file(
    name = "com.google.ar.sceneform_rendering",
    downloaded_file_path = "rendering-1.10.0.aar",
    sha256 = "d2f6cd1d54eee0d5557518d1edcf77a3ba37494ae94f9bb862e570ee426a3431",
    urls = [
        "https://dl.google.com/android/maven2/com/google/ar/sceneform/rendering/1.10.0/rendering-1.10.0.aar",
    ],
)

maven_install(
    name = "testonly_testing",
    artifacts = [
        maven.artifact(
            artifact = "guava",
            group = "com.google.guava",
            version = "27.0-jre",
        ),
        maven.artifact(
            testonly = True,
            artifact = "auto-value-annotations",
            group = "com.google.auto.value",
            version = "1.6.3",
        ),
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

load("@regression_testing//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

load("@regression_testing//:compat.bzl", "compat_repositories")

compat_repositories()

maven_install(
    name = "policy_pinned_testing",
    artifacts = [
        # https://github.com/bazelbuild/rules_jvm_external/issues/107
        "com.google.cloud:google-cloud-storage:1.66.0",
        "com.google.guava:guava:25.0-android",
    ],
    maven_install_json = "//tests/custom_maven_install:policy_pinned_testing_install.json",
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
    version_conflict_policy = "pinned",
)

load(
    "@policy_pinned_testing//:defs.bzl",
    _policy_pinned_maven_install = "pinned_maven_install",
)

_policy_pinned_maven_install()

maven_install(
    name = "strict_visibility_testing",
    artifacts = [
        # https://github.com/bazelbuild/rules_jvm_external/issues/94
        "org.apache.tomcat:tomcat-catalina:9.0.24",
        # https://github.com/bazelbuild/rules_jvm_external/issues/255
        maven.artifact(
            artifact = "jetty-http",
            classifier = "tests",
            group = "org.eclipse.jetty",
            version = "9.4.20.v20190813",
        ),
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    strict_visibility = True,
)

maven_install(
    name = "strict_visibility_with_compat_testing",
    artifacts = [
        # Must not be in any other maven_install where generate_compat_repositories = True
        "com.google.http-client:google-http-client-gson:1.42.3",
    ],
    generate_compat_repositories = True,
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    strict_visibility = True,
)

load("@strict_visibility_with_compat_testing//:compat.bzl", "compat_repositories")

compat_repositories()

maven_install(
    name = "maven_install_in_custom_location",
    artifacts = ["com.google.guava:guava:27.0-jre"],
    maven_install_json = "@rules_jvm_external//tests/custom_maven_install:maven_install.json",
    repositories = ["https://repo1.maven.org/maven2"],
)

load("@maven_install_in_custom_location//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

maven_install(
    name = "jetify_all_test",
    artifacts = [
        "com.google.guava:guava:27.0-jre",
        "com.android.support:appcompat-v7:28.0.0",
        "com.android.support:swiperefreshlayout:28.0.0",
    ],
    jetify = True,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
)

maven_install(
    name = "jetify_include_list_test",
    artifacts = [
        "com.google.guava:guava:27.0-jre",
        "com.android.support:appcompat-v7:28.0.0",
        "com.android.support:swiperefreshlayout:28.0.0",
    ],
    jetify = True,
    jetify_include_list = [
        "com.android.support:appcompat-v7",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
)

maven_install(
    name = "duplicate_version_warning",
    artifacts = [
        "com.fasterxml.jackson.core:jackson-annotations:2.10.1",
        "com.fasterxml.jackson.core:jackson-annotations:2.12.1",
        "com.fasterxml.jackson.core:jackson-annotations:2.10.1",
        "com.fasterxml.jackson.core:jackson-annotations:2.11.2",
        "com.github.jnr:jffi:1.3.4",
        maven.artifact(
            artifact = "jffi",
            classifier = "native",
            group = "com.github.jnr",
            version = "1.3.3",
        ),
        maven.artifact(
            artifact = "jffi",
            classifier = "native",
            group = "com.github.jnr",
            version = "1.3.2",
        ),
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
)

maven_install(
    name = "duplicate_version_warning_same_version",
    artifacts = [
        "com.fasterxml.jackson.core:jackson-annotations:2.10.1",
        "com.fasterxml.jackson.core:jackson-annotations:2.10.1",
        maven.artifact(
            artifact = "jffi",
            classifier = "native",
            group = "com.github.jnr",
            version = "1.3.3",
        ),
        maven.artifact(
            artifact = "jffi",
            classifier = "native",
            group = "com.github.jnr",
            version = "1.3.3",
        ),
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
)

maven_install(
    name = "jvm_import_test",
    artifacts = [
        "com.google.code.findbugs:jsr305:3.0.2",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

maven_install(
    name = "starlark_aar_import_with_sources_test",
    # Not actually necessary since this is the default value, but useful for
    # testing.
    aar_import_bzl_label = "@build_bazel_rules_android//android:rules.bzl",
    artifacts = [
        "androidx.work:work-runtime:2.6.0",
    ],
    fetch_sources = True,
    jetify = False,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
    use_starlark_android_rules = True,
)

maven_install(
    name = "starlark_aar_import_test",
    # Not actually necessary since this is the default value, but useful for
    # testing.
    aar_import_bzl_label = "@build_bazel_rules_android//android:rules.bzl",
    artifacts = [
        "com.android.support:appcompat-v7:28.0.0",
    ],
    fetch_sources = False,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
    use_starlark_android_rules = True,
)

maven_install(
    name = "starlark_aar_import_with_jetify_test",
    # Not actually necessary since this is the default value, but useful for
    # testing.
    aar_import_bzl_label = "@build_bazel_rules_android//android:rules.bzl",
    artifacts = [
        "com.android.support:appcompat-v7:28.0.0",
    ],
    jetify = True,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
    use_starlark_android_rules = True,
)

# for the above "starlark_aar_import_test" maven_install with
# use_starlark_android_rules = True
http_archive(
    name = "build_bazel_rules_android",
    sha256 = "cd06d15dd8bb59926e4d65f9003bfc20f9da4b2519985c27e190cddc8b7a7806",
    strip_prefix = "rules_android-0.1.1",
    urls = ["https://github.com/bazelbuild/rules_android/archive/v0.1.1.zip"],
)

# https://github.com/bazelbuild/rules_jvm_external/issues/351
maven_install(
    name = "json_artifacts_testing",
    artifacts = [
        "org.json:json:20190722",
        "io.quarkus:quarkus-maven-plugin:1.0.1.Final",
        "io.quarkus:quarkus-bom-descriptor-json:1.0.1.Final",
    ],
    fetch_sources = True,
    maven_install_json = "//tests/custom_maven_install:json_artifacts_testing_install.json",
    repositories = [
        "https://repo.maven.apache.org/maven2/",
        "https://repo.spring.io/plugins-release/",
    ],
)

# https://github.com/bazelbuild/rules_jvm_external/issues/433
maven_install(
    name = "version_interval_testing",
    artifacts = [
        "io.grpc:grpc-netty-shaded:1.29.0",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

load(
    "@json_artifacts_testing//:defs.bzl",
    _json_artifacts_testing_install = "pinned_maven_install",
)

_json_artifacts_testing_install()

maven_install(
    name = "m2local_testing",
    artifacts = [
        # this is a test jar built for integration
        # tests in this repo
        "com.example:kt:1.0.0",
    ],
    fail_on_missing_checksum = True,
    repositories = [
        "m2Local",
        "https://repo1.maven.org/maven2",
    ],
)

maven_install(
    name = "m2local_testing_repin",
    artifacts = [
        # this is a test jar built for integration
        # tests in this repo
        "com.example:kt:1.0.0",
    ],
    maven_install_json = "//tests/custom_maven_install:m2local_testing_with_pinned_file_install.json",
    repositories = [
        "m2Local",
        "https://repo1.maven.org/maven2",
    ],
)

load("@m2local_testing_repin//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

maven_install(
    name = "m2local_testing_without_checksum",
    artifacts = [
        # this is a test jar built for integration
        # tests in this repo
        "com.example:kt:1.0.0",
    ],
    # jar won't have checksums for this test case
    fail_on_missing_checksum = False,
    repositories = [
        "m2Local",
        "https://repo1.maven.org/maven2",
    ],
)

maven_install(
    name = "bom_testing",
    artifacts = [
        # The version of this will be set by the BOM
        "io.netty:netty-codec",
    ],
    #    boms = [
    #        "io.netty:netty-bom:4.1.89.Final",
    #    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

maven_install(
    name = "v1_lock_file_format",
    artifacts = [
        # Coordinates that are in no other `maven_install`
        "org.seleniumhq.selenium:selenium-remote-driver:4.8.0",
    ],
    generate_compat_repositories = True,
    maven_install_json = "//tests/custom_maven_install:v1_lock_file_format_install.json",
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)

load("@v1_lock_file_format//:defs.bzl", v1_lock_file_format_pinned_maven_install = "pinned_maven_install")

v1_lock_file_format_pinned_maven_install()

http_file(
    name = "hamcrest_core_for_test",
    downloaded_file_path = "hamcrest-core-1.3.jar",
    sha256 = "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9",
    urls = [
        "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
    ],
)

http_file(
    name = "hamcrest_core_srcs_for_test",
    downloaded_file_path = "hamcrest-core-1.3-sources.jar",
    sha256 = "e223d2d8fbafd66057a8848cc94222d63c3cedd652cc48eddc0ab5c39c0f84df",
    urls = [
        "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3-sources.jar",
    ],
)

http_file(
    name = "gson_for_test",
    downloaded_file_path = "gson-2.9.0.jar",
    sha256 = "c96d60551331a196dac54b745aa642cd078ef89b6f267146b705f2c2cbef052d",
    urls = [
        "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.9.0/gson-2.9.0.jar",
    ],
)

http_file(
    name = "junit_platform_commons_for_test",
    downloaded_file_path = "junit-platform-commons-1.8.2.jar",
    sha256 = "d2e015fca7130e79af2f4608dc54415e4b10b592d77333decb4b1a274c185050",
    urls = [
        "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-commons/1.8.2/junit-platform-commons-1.8.2.jar",
    ],
)

# https://github.com/bazelbuild/rules_jvm_external/issues/865
http_file(
    name = "google_api_services_compute_javadoc_for_test",
    downloaded_file_path = "google-api-services-compute-v1-rev235-1.25.0-javadoc.jar",
    sha256 = "b03be5ee8effba3bfbaae53891a9c01d70e2e3bd82ad8889d78e641b22bd76c2",
    urls = [
        "https://repo1.maven.org/maven2/com/google/apis/google-api-services-compute/v1-rev235-1.25.0/google-api-services-compute-v1-rev235-1.25.0-javadoc.jar",
    ],
)

# End test dependencies

http_archive(
    name = "bazel_toolchains",
    sha256 = "179ec02f809e86abf56356d8898c8bd74069f1bd7c56044050c2cd3d79d0e024",
    strip_prefix = "bazel-toolchains-4.1.0",
    urls = [
        "https://github.com/bazelbuild/bazel-toolchains/releases/download/4.1.0/bazel-toolchains-4.1.0.tar.gz",
        "https://mirror.bazel.build/github.com/bazelbuild/bazel-toolchains/releases/download/4.1.0/bazel-toolchains-4.1.0.tar.gz",
    ],
)

http_archive(
    name = "bazelci_rules",
    sha256 = "eca21884e6f66a88c358e580fd67a6b148d30ab57b1680f62a96c00f9bc6a07e",
    strip_prefix = "bazelci_rules-1.0.0",
    url = "https://github.com/bazelbuild/continuous-integration/releases/download/rules-1.0.0/bazelci_rules-1.0.0.tar.gz",
)

load("@bazelci_rules//:rbe_repo.bzl", "rbe_preconfig")

# Creates a default toolchain config for RBE.
# Use this as is if you are using the rbe_ubuntu16_04 container,
# otherwise refer to RBE docs.
rbe_preconfig(
    name = "buildkite_config",
    toolchain = "ubuntu1804-bazel-java11",
)

load("//migration:maven_jar_migrator_deps.bzl", "maven_jar_migrator_repositories")

maven_jar_migrator_repositories()
