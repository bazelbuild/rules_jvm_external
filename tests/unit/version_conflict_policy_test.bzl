"""Tests for resolver-specific version conflict policies."""

load("@bazel_skylib//lib:partial.bzl", "partial")
load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//private/lib:coordinates.bzl", "unpack_coordinates")
load("//private/lib:layering.bzl", "apply_root_version_conflict_policy")

def _pinned_policy_forces_versioned_root_artifacts_impl(ctx):
    env = unittest.begin(ctx)

    for resolver in ["gradle", "maven"]:
        versioned = unpack_coordinates("com.example:root:1.0")
        versionless = unpack_coordinates("com.example:managed-by-bom")

        artifacts = apply_root_version_conflict_policy(
            [versioned, versionless],
            resolver,
            "pinned",
        )

        asserts.true(env, artifacts[0].force_version)
        asserts.false(env, hasattr(artifacts[1], "force_version"))

    return unittest.end(env)

pinned_policy_forces_versioned_root_artifacts_test = unittest.make(_pinned_policy_forces_versioned_root_artifacts_impl)

def _other_policies_leave_root_artifacts_unchanged_impl(ctx):
    env = unittest.begin(ctx)

    artifact = unpack_coordinates("com.example:root:1.0")

    default_artifacts = apply_root_version_conflict_policy([artifact], "gradle", "default")
    coursier_artifacts = apply_root_version_conflict_policy([artifact], "coursier", "pinned")

    asserts.false(env, hasattr(default_artifacts[0], "force_version"))
    asserts.false(env, hasattr(coursier_artifacts[0], "force_version"))

    return unittest.end(env)

other_policies_leave_root_artifacts_unchanged_test = unittest.make(_other_policies_leave_root_artifacts_unchanged_impl)

def _pinned_policy_forces_one_version_per_gradle_module_impl(ctx):
    env = unittest.begin(ctx)

    fixtures = unpack_coordinates("com.example:lib:jar:test-fixtures:1.0")
    main = unpack_coordinates("com.example:lib:2.0")
    managed = unpack_coordinates("com.example:managed-by-bom")

    artifacts = apply_root_version_conflict_policy(
        [fixtures, main, managed],
        "gradle",
        "pinned",
    )

    # Gradle selects a single version per group:artifact module, ignoring
    # classifiers, so only the unclassified root may force the module version.
    asserts.false(env, getattr(artifacts[0], "force_version", False))
    asserts.true(env, getattr(artifacts[1], "force_version", False))
    asserts.false(env, hasattr(artifacts[2], "force_version"))

    return unittest.end(env)

pinned_policy_forces_one_version_per_gradle_module_test = unittest.make(_pinned_policy_forces_one_version_per_gradle_module_impl)

def _pinned_policy_prefers_unclassified_gradle_root_over_newer_classified_impl(ctx):
    env = unittest.begin(ctx)

    newer_fixtures = unpack_coordinates("com.example:lib:jar:test-fixtures:2.0")
    older_main = unpack_coordinates("com.example:lib:1.0")

    artifacts = apply_root_version_conflict_policy(
        [newer_fixtures, older_main],
        "gradle",
        "pinned",
    )

    asserts.false(env, getattr(artifacts[0], "force_version", False))
    asserts.true(env, getattr(artifacts[1], "force_version", False))

    return unittest.end(env)

pinned_policy_prefers_unclassified_gradle_root_over_newer_classified_test = unittest.make(_pinned_policy_prefers_unclassified_gradle_root_over_newer_classified_impl)

def _pinned_policy_pins_classifier_only_gradle_root_impl(ctx):
    env = unittest.begin(ctx)

    fixtures = unpack_coordinates("com.example:lib:jar:test-fixtures:1.0")

    artifacts = apply_root_version_conflict_policy([fixtures], "gradle", "pinned")

    asserts.true(env, getattr(artifacts[0], "force_version", False))

    return unittest.end(env)

pinned_policy_pins_classifier_only_gradle_root_test = unittest.make(_pinned_policy_pins_classifier_only_gradle_root_impl)

def _pinned_policy_forces_highest_version_across_classified_only_gradle_roots_impl(ctx):
    env = unittest.begin(ctx)

    older = unpack_coordinates("com.example:lib:jar:sources:1.0")
    newer = unpack_coordinates("com.example:lib:jar:test-fixtures:2.0")

    artifacts = apply_root_version_conflict_policy([older, newer], "gradle", "pinned")

    asserts.false(env, getattr(artifacts[0], "force_version", False))
    asserts.true(env, getattr(artifacts[1], "force_version", False))

    return unittest.end(env)

pinned_policy_forces_highest_version_across_classified_only_gradle_roots_test = unittest.make(_pinned_policy_forces_highest_version_across_classified_only_gradle_roots_impl)

def _pinned_policy_forces_each_maven_root_independently_impl(ctx):
    env = unittest.begin(ctx)

    main = unpack_coordinates("com.example:lib:2.0")
    fixtures = unpack_coordinates("com.example:lib:jar:test-fixtures:1.0")

    artifacts = apply_root_version_conflict_policy([main, fixtures], "maven", "pinned")

    # Maven mediates each classified form independently, so both stay forced.
    asserts.true(env, artifacts[0].force_version)
    asserts.true(env, artifacts[1].force_version)

    return unittest.end(env)

pinned_policy_forces_each_maven_root_independently_test = unittest.make(_pinned_policy_forces_each_maven_root_independently_impl)

def version_conflict_policy_test_suite():
    unittest.suite(
        "version_conflict_policy_tests",
        partial.make(pinned_policy_forces_versioned_root_artifacts_test, size = "small"),
        partial.make(other_policies_leave_root_artifacts_unchanged_test, size = "small"),
        partial.make(pinned_policy_forces_one_version_per_gradle_module_test, size = "small"),
        partial.make(pinned_policy_prefers_unclassified_gradle_root_over_newer_classified_test, size = "small"),
        partial.make(pinned_policy_pins_classifier_only_gradle_root_test, size = "small"),
        partial.make(pinned_policy_forces_highest_version_across_classified_only_gradle_roots_test, size = "small"),
        partial.make(pinned_policy_forces_each_maven_root_independently_test, size = "small"),
    )
