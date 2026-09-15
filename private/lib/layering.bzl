"""Support for layering Maven dependencies contributed by bzlmod modules."""

load("@bazel_skylib//lib:new_sets.bzl", "sets")
load("//private/lib:coordinates.bzl", "to_key")
load("//private/rules:maven_version.bzl", "compare_maven_versions")

DEFAULT_NAME = "maven"

def _diagnostic(text, gate):
    return struct(text = text, gate = gate)

def should_print_diagnostic(diagnostic, repin, verbose):
    """Whether a layering diagnostic is enabled for the current environment."""
    return (
        diagnostic.gate == "always" or
        (diagnostic.gate == "repin" and repin) or
        (diagnostic.gate == "verbose" and verbose) or
        (diagnostic.gate == "repin_verbose" and repin and verbose)
    )

def contributing_modules_warning(repo_name, known_contributing_modules, non_root_bazel_dep_to_items):
    """Returns the warning for contributions from modules not acknowledged by the root."""
    contributing_module_names = non_root_bazel_dep_to_items.keys()
    new_contributing_modules = sets.difference(sets.make(contributing_module_names), known_contributing_modules)
    if sets.length(new_contributing_modules) > 0:
        return (
            "The maven repository '%s' has contributions from multiple bzlmod modules, and will be resolved together: %s." % (
                repo_name,
                sorted(contributing_module_names),
            ) + "\nSee https://github.com/bazel-contrib/rules_jvm_external/blob/master/docs/bzlmod.md#module-dependency-layering" +
            " for more information. \n" +
            " To suppress this warning review the contributions from the other modules and add the following attribute" +
            " in the root MODULE.bazel file: \n" +
            "maven.install(\n" +
            ("  name = \"{0}\"\n".format(repo_name) if repo_name != DEFAULT_NAME else "") +
            "  known_contributing_modules = {0},\n".format(sorted(contributing_module_names)) +
            "  ...\n" +
            ")"
        )
    return None

def _candidate_takes_precedence(current_artifact, candidate_artifact):
    if current_artifact == None:
        return True

    current_forced = getattr(current_artifact, "force_version", False)
    candidate_forced = getattr(candidate_artifact, "force_version", False)
    if current_forced != candidate_forced:
        return candidate_forced

    return compare_maven_versions(current_artifact.version, candidate_artifact.version) == -1

def _fail_if_conflicting_forces(module_name, artifacts):
    coordinate_to_forced_artifact = {}
    for artifact in artifacts:
        if not getattr(artifact, "force_version", False):
            continue

        artifact_key = to_key(artifact)
        previous_artifact = coordinate_to_forced_artifact.get(artifact_key)
        if previous_artifact != None and compare_maven_versions(previous_artifact.version, artifact.version) != 0:
            fail(
                "Module '%s' forces dependency '%s' at different versions: %s and %s." % (
                    module_name,
                    artifact_key,
                    previous_artifact.version,
                    artifact.version,
                ),
            )
        coordinate_to_forced_artifact[artifact_key] = artifact

def deduplicate_non_root_artifacts(
        bazel_dep_to_non_root_artifacts,
        return_only_artifacts = False,
        root_forced_artifact_keys = None):
    root_forced_artifact_keys = root_forced_artifact_keys or {}
    coordinate_to_artifact = {}
    coordinate_to_forced_artifact = {}
    for bazel_dep_name in bazel_dep_to_non_root_artifacts:
        module_coordinate_to_artifact = {}
        module_artifacts = bazel_dep_to_non_root_artifacts.get(bazel_dep_name, [])
        _fail_if_conflicting_forces(bazel_dep_name, module_artifacts)
        for artifact in module_artifacts:
            if not getattr(artifact, "testonly", False):
                artifact_key = to_key(artifact)
                if _candidate_takes_precedence(module_coordinate_to_artifact.get(artifact_key), artifact):
                    module_coordinate_to_artifact[artifact_key] = artifact

        for artifact_key, artifact in module_coordinate_to_artifact.items():
            if getattr(artifact, "force_version", False) and artifact_key not in root_forced_artifact_keys:
                previous_force = coordinate_to_forced_artifact.get(artifact_key)
                if previous_force:
                    previous_bazel_dep_name, previous_artifact = previous_force
                    if compare_maven_versions(previous_artifact.version, artifact.version) != 0:
                        fail(
                            "Conflicting forced versions for dependency '%s': %s wants %s, %s wants %s. " % (
                                artifact_key,
                                previous_bazel_dep_name,
                                previous_artifact.version,
                                bazel_dep_name,
                                artifact.version,
                            ) +
                            "Add an `artifact` tag to the root module at the version you want and set `force_version = True`.",
                        )
                else:
                    coordinate_to_forced_artifact[artifact_key] = (bazel_dep_name, artifact)

            current = coordinate_to_artifact.get(artifact_key)
            current_artifact = current[1] if current else None
            if _candidate_takes_precedence(current_artifact, artifact):
                coordinate_to_artifact[artifact_key] = (bazel_dep_name, artifact)

    if return_only_artifacts:
        return [v[1] for v in coordinate_to_artifact.values()]
    else:
        return coordinate_to_artifact

# Each bzlmod module may contribute jars to different rules_jvm_external maven repo namespaces.
# We emit a warning to the user if a module overrides an artifact version in the root maven repo.
#
# This can be typical for the default @maven namespace, if a bzlmod dependency
# wishes to contribute to the users' jars.
def merge_with_root_priority(
        name,
        root_artifacts,
        bazel_dep_to_non_root_artifacts,
        duplicate_version_warning = "warn"):
    """Deduplicate artifacts, giving priority to root module artifacts with force_version set."""
    root_forced_artifact_keys = {
        to_key(artifact): True
        for artifact in root_artifacts
        if getattr(artifact, "force_version", False)
    }
    non_root_coordinate_to_artifact = deduplicate_non_root_artifacts(
        bazel_dep_to_non_root_artifacts,
        root_forced_artifact_keys = root_forced_artifact_keys,
    )

    duplicate_artifact_warning = ""
    filtered_root_artifacts = []
    filtered_non_root_artifacts = []
    for root_artifact in root_artifacts:
        keep_root_artifact = True
        artifact_key = to_key(root_artifact)
        if artifact_key in non_root_coordinate_to_artifact:
            bazel_dep_name, non_root_artifact = non_root_coordinate_to_artifact.pop(artifact_key)
            if not getattr(root_artifact, "force_version", False):
                comparison = compare_maven_versions(root_artifact.version, non_root_artifact.version)
                non_root_forced = getattr(non_root_artifact, "force_version", False)
                if non_root_forced or comparison == -1:
                    keep_root_artifact = False
                    filtered_non_root_artifacts.append(non_root_artifact)

                if comparison != 0 and (non_root_forced or comparison == -1):
                    message = (
                        "For dependency '%s:%s' the root @%s repo wants version %s, " % (root_artifact.group, root_artifact.artifact, name, root_artifact.version) +
                        "but got %s from the %s bazel dep. " % (non_root_artifact.version, bazel_dep_name) +
                        "Please update the version in your MODULE.bazel or set `force_version = True`."
                    )
                    if duplicate_version_warning == "error":
                        fail(message)
                    elif duplicate_version_warning == "warn":
                        duplicate_artifact_warning = duplicate_artifact_warning + "\nWARNING: " + message
        if keep_root_artifact:
            filtered_root_artifacts.append(root_artifact)

    # Add any remaining non root artifacts that weren't found in the root artifact list
    addtional_artifact_message = ""
    for bazel_dep_name, non_root_artifact in non_root_coordinate_to_artifact.values():
        addtional_artifact_message = addtional_artifact_message + (
            "\nINFO: The @%s repo is getting the additional artifact %s:%s:%s from the %s bazel dep." % (name, non_root_artifact.group, non_root_artifact.artifact, non_root_artifact.version, bazel_dep_name)
        )
        filtered_non_root_artifacts.append(non_root_artifact)

    diagnostics = []
    if duplicate_artifact_warning != "":
        diagnostics.append(_diagnostic(duplicate_artifact_warning, "always"))
    if addtional_artifact_message != "":
        diagnostics.append(_diagnostic(addtional_artifact_message, "repin_verbose"))

    return struct(
        artifacts = filtered_root_artifacts + filtered_non_root_artifacts,
        diagnostics = diagnostics,
    )

def filter_known_contributing_modules(name, known_contributing_modules, bazel_dep_to_items, item_kind):
    """Filters contributions to modules acknowledged by the root."""
    all_non_root_modules = bazel_dep_to_items.keys()
    filtered = {
        module: bazel_dep_to_items[module]
        for module in sets.to_list(known_contributing_modules)
        if module in bazel_dep_to_items
    }
    diagnostics = []
    for module in all_non_root_modules:
        if module not in filtered:
            diagnostics.append(_diagnostic(
                "\nINFO: The @%s repo is not using %s from %s because it is not in the known_contributing_modules" % (name, item_kind, module),
                "verbose",
            ))
    return struct(filtered = filtered, diagnostics = diagnostics)

def layer_maven_namespace(
        name,
        root_present,
        root_artifacts,
        root_boms,
        resolver,
        version_conflict_policy,
        duplicate_version_warning,
        known_contributing_modules,
        bazel_dep_to_non_root_artifacts,
        bazel_dep_to_non_root_boms):
    """Layers the dependency declarations for one Maven repository namespace."""
    _fail_if_conflicting_forces("root", root_artifacts)
    _fail_if_conflicting_forces("root", root_boms)
    root_artifacts = apply_root_version_conflict_policy(
        root_artifacts,
        resolver,
        version_conflict_policy,
    )
    diagnostics = []

    if not root_present:
        return struct(
            artifacts = deduplicate_non_root_artifacts(bazel_dep_to_non_root_artifacts, True),
            boms = deduplicate_non_root_artifacts(bazel_dep_to_non_root_boms, True),
            diagnostics = diagnostics,
        )

    if sets.length(known_contributing_modules) == 0:
        warning = contributing_modules_warning(
            name,
            known_contributing_modules,
            bazel_dep_to_non_root_artifacts | bazel_dep_to_non_root_boms,
        )
        if warning:
            diagnostics.append(_diagnostic(warning, "always"))
    else:
        filtered_artifacts = filter_known_contributing_modules(
            name,
            known_contributing_modules,
            bazel_dep_to_non_root_artifacts,
            "deps",
        )
        bazel_dep_to_non_root_artifacts = filtered_artifacts.filtered
        diagnostics.extend(filtered_artifacts.diagnostics)

        filtered_boms = filter_known_contributing_modules(
            name,
            known_contributing_modules,
            bazel_dep_to_non_root_boms,
            "boms",
        )
        bazel_dep_to_non_root_boms = filtered_boms.filtered
        diagnostics.extend(filtered_boms.diagnostics)

    artifacts = merge_with_root_priority(
        name,
        root_artifacts,
        bazel_dep_to_non_root_artifacts,
        duplicate_version_warning,
    )
    diagnostics.extend(artifacts.diagnostics)

    boms = merge_with_root_priority(
        name,
        root_boms,
        bazel_dep_to_non_root_boms,
        duplicate_version_warning,
    )
    diagnostics.extend(boms.diagnostics)

    return struct(
        artifacts = artifacts.artifacts,
        boms = boms.artifacts,
        diagnostics = diagnostics,
    )

def remove_empty_fields(s):
    """Used for reducing an artifact struct down to only those fields that have values"""
    return {
        k: getattr(s, k)
        for k in dir(s)
        if k != "to_json" and k != "to_proto" and getattr(s, k, None)
    } | {"version": getattr(s, "version", "")}

def _defines_gradle_module_version(candidate, current):
    """Whether candidate should force the Gradle module version instead of current."""
    candidate_classified = bool(getattr(candidate, "classifier", None))
    current_classified = bool(getattr(current, "classifier", None))
    if candidate_classified != current_classified:
        # An unclassified root defines the module version.
        return current_classified
    return compare_maven_versions(candidate.version, current.version) == 1

def _select_gradle_forced_versions(artifacts):
    """Selects the single version to force for each Gradle group:artifact module.

    Gradle resolves one version per module regardless of classifier, so forcing
    two versions of the same module (for example a main jar and its
    test-fixtures jar) makes resolution unsatisfiable.
    """
    winners = {}
    for artifact in artifacts:
        if not getattr(artifact, "version", None):
            continue
        key = "%s:%s" % (artifact.group, artifact.artifact)
        current = winners.get(key)
        if current == None or _defines_gradle_module_version(artifact, current):
            winners[key] = artifact
    return {key: winner.version for key, winner in winners.items()}

def _forces_gradle_module_version(artifact, forced_versions):
    version = getattr(artifact, "version", None)
    if not version:
        return False
    return version == forced_versions.get("%s:%s" % (artifact.group, artifact.artifact))

def apply_root_version_conflict_policy(artifacts, resolver, version_conflict_policy):
    """Applies the install-level conflict policy to root module artifacts."""
    if resolver not in ["gradle", "maven"] or version_conflict_policy != "pinned":
        return artifacts

    if resolver == "gradle":
        forced_versions = _select_gradle_forced_versions(artifacts)
        return [
            struct(**(remove_empty_fields(artifact) | {"force_version": True})) if _forces_gradle_module_version(artifact, forced_versions) else artifact
            for artifact in artifacts
        ]

    return [
        struct(**(remove_empty_fields(artifact) | {"force_version": True})) if getattr(artifact, "version", None) else artifact
        for artifact in artifacts
    ]
