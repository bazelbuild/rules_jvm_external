load("@bazel_features//:features.bzl", "bazel_features")
load("@bazel_skylib//lib:new_sets.bzl", "sets")
load("@toml.bzl", "toml")
load("//:specs.bzl", "parse", _json = "json")
load("//private:compat_repository.bzl", "compat_repository")
load(
    "//private:coursier_utilities.bzl",
    "contains_git_conflict_markers",
    "escape",
    "strip_packaging_and_classifier_and_version",
)
load("//private/lib:coordinates.bzl", "to_external_form", "unpack_coordinates")
load(
    "//private/lib:layering.bzl",
    "DEFAULT_NAME",
    "layer_maven_namespace",
    "remove_empty_fields",
    "should_print_diagnostic",
)
load("//private/rules:coursier.bzl", "DEFAULT_AAR_IMPORT_LABEL", "coursier_fetch", "pinned_coursier_fetch")
load("//private/rules:unpinned_maven_pin_command_alias.bzl", "unpinned_maven_pin_command_alias")
load("//private/rules:v1_lock_file.bzl", "v1_lock_file")
load("//private/rules:v3_lock_file.bzl", "v2_lock_file", "v3_lock_file")
load(":download_pinned_deps.bzl", "download_pinned_deps")

DEFAULT_REPOSITORIES = [
    "https://repo1.maven.org/maven2",
]

_DEFAULT_RESOLVER = "coursier"

artifact = tag_class(
    doc = "Used to define a single artifact where the simple coordinates are insufficient. Will be added to the other artifacts declared by tags with the same `name` attribute.",
    attrs = {
        "name": attr.string(default = DEFAULT_NAME),
        "group": attr.string(mandatory = True),
        "artifact": attr.string(mandatory = True),
        "version": attr.string(),
        "packaging": attr.string(),
        "classifier": attr.string(),
        "force_version": attr.bool(default = False),
        "neverlink": attr.bool(),
        "testonly": attr.bool(),
        "exclusions": attr.string_list(doc = "Maven artifact tuples, in `artifactId:groupId` format", allow_empty = True),
    },
)

install = tag_class(
    doc = "Combines artifact and bom declarations with setting the location of lock files to use, and repositories to download artifacts from. There can only be one `install` tag with a given `name` per module. `install` tags with the same name across multiple modules will be merged, with the root module taking precedence.",
    attrs = {
        "name": attr.string(default = DEFAULT_NAME),

        # Actual artifacts and overrides
        "artifacts": attr.string_list(doc = "Maven artifact tuples, in `artifactId:groupId:version` format", allow_empty = True),
        "boms": attr.string_list(doc = "Maven BOM tuples, in `artifactId:groupId:version` format", allow_empty = True),
        "exclusions": attr.string_list(doc = "Maven artifact tuples, in `artifactId:groupId` format", allow_empty = True),

        # What do we fetch?
        "fetch_javadoc": attr.bool(default = False),
        "fetch_sources": attr.bool(default = False),

        # How do we do artifact resolution?
        "resolver": attr.string(doc = "The resolver to use. Only honoured for the root module.", values = ["coursier", "gradle", "maven"], default = _DEFAULT_RESOLVER),

        # Controlling visibility
        "strict_visibility": attr.bool(
            doc = """Controls visibility of transitive dependencies.

            If "True", transitive dependencies are private and invisible to user's rules.
            If "False", transitive dependencies are public and visible to user's rules.
            """,
            default = False,
        ),
        "strict_visibility_value": attr.label_list(default = ["//visibility:private"]),

        # Android support
        "aar_import_bzl_label": attr.string(default = DEFAULT_AAR_IMPORT_LABEL, doc = "The label (as a string) to use to import aar_import from"),
        "use_starlark_android_rules": attr.bool(default = False, doc = "Whether to use the native or Starlark version of the Android rules."),

        # Configuration "stuff"
        "additional_netrc_lines": attr.string_list(doc = "Additional lines prepended to the netrc file used by `http_file` (with `maven_install_json` only).", default = []),
        "use_credentials_from_home_netrc_file": attr.bool(doc = "Whether to pass machine login credentials from the ~/.netrc file to coursier.", default = False),
        "duplicate_version_warning": attr.string(
            doc = """What to do if layering selects a non-root version instead of the root version, or if the root module declares multiple versions of the same coordinate

            If "error", then print a message and fail the build.
            If "warn", then print a warning and continue.
            If "none", then do nothing.
            """,
            default = "warn",
            values = [
                "error",
                "warn",
                "none",
            ],
        ),
        "fail_if_repin_required": attr.bool(doc = "Whether to fail the build if the maven_artifact inputs have changed but the lock file has not been repinned.", default = True),
        "lock_file": attr.label(),
        "index_file": attr.label(doc = "If present, when dependencies are resolved this file will contain information the java gazelle plugin can use to more accurately construct build files. The default name should be `maven_index.json`."),
        "repositories": attr.string_list(default = DEFAULT_REPOSITORIES),
        "generate_compat_repositories": attr.bool(
            doc = "Additionally generate repository aliases in a .bzl file for all JAR artifacts. For example, `@maven//:com_google_guava_guava` can also be referenced as `@com_google_guava_guava//jar`.",
        ),
        "known_contributing_modules": attr.string_list(
            doc = "List of Bzlmod modules that are known to be contributing to this repository. Only honoured for the root module.",
            default = [],
        ),

        # When using an unpinned repo
        "excluded_artifacts": attr.string_list(doc = "Artifacts to exclude, in `artifactId:groupId` format. Only used on unpinned installs", default = []),  # list of artifacts to exclude
        "fail_on_missing_checksum": attr.bool(default = True),
        "resolve_timeout": attr.int(default = 600),
        "version_conflict_policy": attr.string(
            doc = """Policy for user-defined vs. transitive dependency version conflicts

            If "pinned", choose the user-specified version in maven_install unconditionally.
            With the Gradle and Maven resolvers, this only applies to artifacts contributed by the root module.
            If "default", follow the selected resolver's default policy.
            """,
            default = "default",
            values = [
                "default",
                "pinned",
            ],
        ),
        "ignore_empty_files": attr.bool(default = False, doc = "Treat jars that are empty as if they were not found."),
        "repin_instructions": attr.string(doc = "Instructions to re-pin the repository if required. Many people have wrapper scripts for keeping dependencies up to date, and would like to point users to that instead of the default. Only honoured for the root module."),
        "additional_coursier_options": attr.string_list(doc = "Additional options that will be passed to coursier."),
        "resolver_extra_dependencies": attr.label_list(default = [], doc = "Jars or libraries to add to the resolver classpath (such as custom MetadataService or DownloadService SPI implementations)."),
    },
)

override = tag_class(
    doc = "Allows specific maven coordinates to be redirected elsewhere. Commonly used to replace an external dependency with another, or a compatible implementation from within this module.",
    attrs = {
        "name": attr.string(default = DEFAULT_NAME),
        "coordinates": attr.string(doc = "Maven artifact tuple in `artifactId:groupId` format", mandatory = True),
        "target": attr.label(doc = "Target to use in place of maven coordinates", mandatory = True),
        "visibility": attr.string_list(doc = "Visibility of the generated alias target", default = []),
    },
)

from_toml = tag_class(
    doc = "Allows a project to import dependencies from a Gradle format `libs.versions.toml` file.",
    attrs = {
        "name": attr.string(default = DEFAULT_NAME),
        "libs_versions_toml": attr.label(doc = "Gradle `libs.versions.toml` file to use", mandatory = True),
        "bom_modules": attr.string_list(doc = "List of modules in `group:artifact` format to treat as BOMs, not artifacts"),
    },
)

amend_artifact = tag_class(
    doc = "Modifies an artifact with `coordinates` defined in other tags with additional properties.",
    attrs = {
        "name": attr.string(default = DEFAULT_NAME),
        "coordinates": attr.string(doc = "Coordinates of the artifact to amend. Only `group:artifact` are used for matching.", mandatory = True),
        "force_version": attr.string(values = ["on", "off", "true", "false", ""], default = ""),
        "neverlink": attr.string(values = ["on", "off", "true", "false", ""], default = ""),
        "testonly": attr.string(values = ["on", "off", "true", "false", ""], default = ""),
        "exclusions": attr.string_list(doc = "Maven artifact tuples, in `artifactId:groupId` format", allow_empty = True),
    },
)

def _logical_or(source, key, default_value, new_value):
    current = source.get(key, default_value)
    source[key] = current or new_value

def _fail_if_different(attribute, current, next, allowed_default_values):
    if current == next:
        return current

    if next in allowed_default_values:
        return current

    if current in allowed_default_values:
        return next

    fail("Expected values for '%s' to be either default or the same. Instead got: %s and %s" % (attribute, current, next))

def _add_exclusions(exclusions):
    to_return = []

    for exclusion in parse.parse_exclusion_spec_list(exclusions):
        if exclusion not in to_return:
            to_return.append(exclusion)
    return to_return

def _generate_compat_repos(name, existing_compat_repos, artifacts):
    seen = []

    for artifact in artifacts:
        coords = to_external_form(artifact)
        versionless = escape(strip_packaging_and_classifier_and_version(coords))
        if versionless in existing_compat_repos:
            continue
        seen.append(versionless)
        existing_compat_repos.append(versionless)
        compat_repository(
            name = versionless,
            generating_repository = name,
            target_name = versionless,
        )

    return seen

def _get_tri_state_bool(amend_val, original_val):
    if amend_val in ["true", "on"]:
        return True
    if amend_val in ["false", "off"]:
        return False
    return original_val

def _amend_artifact(original_artifact, amend):
    """Apply amendments to an artifact struct, returning a new amended struct."""

    # Handle exclusions by merging with existing ones
    existing_exclusions = getattr(original_artifact, "exclusions", []) or []
    final_exclusions = existing_exclusions
    if amend.exclusions:
        new_exclusions = _add_exclusions(amend.exclusions)
        final_exclusions = existing_exclusions + new_exclusions

    # Create new struct with amendments applied

    return struct(
        group = original_artifact.group,
        artifact = original_artifact.artifact,
        version = getattr(original_artifact, "version", None),
        packaging = getattr(original_artifact, "packaging", None),
        classifier = getattr(original_artifact, "classifier", None),
        force_version = _get_tri_state_bool(amend.force_version, getattr(original_artifact, "force_version", None)),
        neverlink = _get_tri_state_bool(amend.neverlink, getattr(original_artifact, "neverlink", None)),
        testonly = _get_tri_state_bool(amend.testonly, getattr(original_artifact, "testonly", None)),
        exclusions = final_exclusions if final_exclusions else None,
    )

def _coordinates_match(artifact, coordinates):
    """Check if an artifact's `group` and `artifact` matches the given coordinate string."""
    coords = unpack_coordinates(coordinates)
    return (artifact.group == coords.group and
            artifact.artifact == coords.artifact)

def apply_amendment(amend, artifacts, boms):
    """Applies an `amend_artifact` to matching entries in `artifacts` and `boms`.

    Matching is by `group:artifact`, and matched entries are amended in place.
    Both lists are searched: a BOM declared via `install(boms = ...)` or
    `from_toml` can be amended through the same mechanism as a regular
    artifact.

    Args:
        amend: The `amend_artifact` tag whose values should be applied.
        artifacts: List of artifact structs to amend in place.
        boms: List of BOM structs to amend in place.

    Returns:
        `True` if at least one artifact or BOM matched the amendment.
    """
    amended = False
    for collection in (artifacts, boms):
        for i, entry in enumerate(collection):
            if _coordinates_match(entry, amend.coordinates):
                collection[i] = _amend_artifact(entry, amend)
                amended = True
    return amended

def process_gradle_versions_file(parsed, bom_modules):
    artifacts = []
    boms = []

    for alias, value in parsed.get("libraries", {}).items():
        # Handle different dependency declaration formats
        coords = None

        # Case 1: Simple string notation: "group:artifact:version"
        if type(value) == "string":
            coords = value
            # Case 2: Map notation

        elif type(value) == "dict":
            # Case 2a: Map with "module" key
            if "module" in value.keys():
                coords = value["module"]
                # Case 2b: Map with "group" and "name" keys

            elif "group" in value.keys() and "name" in value.keys():
                coords = "%s:%s" % (value["group"], value["name"])
            else:
                fail("Library '%s' must have either 'module' or both 'group' and 'name' keys" % alias)

            # Handle version (applies to both module and group+name formats)
            if "version.ref" in value.keys():
                version = parsed.get("versions", {}).get(value["version.ref"])
                if not version:
                    fail("Unable to resolve version.ref %s" % value["version.ref"])
                coords += ":%s" % version
            elif "version" in value.keys():
                raw_version = value["version"]
                if type(raw_version) == "string":
                    coords += ":%s" % value["version"]
                elif type(raw_version) == "dict":
                    version_ref = raw_version.get("ref", None)
                    if not version_ref:
                        fail("Unable to find version ref in", value)
                    version = parsed.get("versions", {}).get(version_ref)
                    if not version:
                        fail("Unable to resolve version ref", value)
                    coords += ":%s" % version

            # Handle packaging (e.g., "aar" for Android libraries)
            # Note: Gradle uses "package" but we'll check common variants
            packaging = value.get("package", value.get("packaging", "jar"))
            if packaging != "jar":
                coords += "@%s" % packaging
        else:
            fail("Library '%s' has unsupported format: %s" % (alias, type(value)))

        # Determine the module identifier for BOM checking
        # Extract just group:artifact for comparison with bom_modules
        module_id = coords.split(":")[0] + ":" + coords.split(":")[1] if ":" in coords else coords

        is_bom = type(value) == "dict" and value.get("is_bom", "false").lower() == "true"

        if is_bom or module_id in bom_modules:
            boms.append(unpack_coordinates(coords))
        else:
            artifact = unpack_coordinates(coords)

            if type(value) == "dict":
                artifact_dict = {
                    "group": artifact.group,
                    "artifact": artifact.artifact,
                    "version": getattr(artifact, "version", None),
                    "packaging": getattr(artifact, "packaging", None),
                    "classifier": getattr(artifact, "classifier", None),
                }
                if "classifier" in value.keys():
                    artifact_dict["classifier"] = value["classifier"]
                if "exclusions" in value.keys():
                    artifact_dict["exclusions"] = _add_exclusions(json.decode(value["exclusions"].replace("'", '"')))
                if "force_version" in value.keys():
                    artifact_dict["force_version"] = (value["force_version"].lower() == "true")
                artifact = struct(**artifact_dict)

            artifacts.append(artifact)

    return artifacts, boms

def _add_artifacts_to_repo(repo, mod, new_artifacts):
    if mod.is_root:
        artifacts = repo.get("artifacts", [])
        repo["artifacts"] = artifacts + new_artifacts
    else:
        if not "bazel_dep_to_artifacts" in repo:
            repo["bazel_dep_to_artifacts"] = {}
        artifacts = repo["bazel_dep_to_artifacts"].get(mod.name, [])
        repo["bazel_dep_to_artifacts"][mod.name] = artifacts + new_artifacts

def _add_boms_to_repo(repo, mod, new_boms):
    if mod.is_root:
        boms = repo.get("boms", [])
        repo["boms"] = boms + new_boms
    else:
        if not "bazel_dep_to_boms" in repo:
            repo["bazel_dep_to_boms"] = {}
        boms = repo["bazel_dep_to_boms"].get(mod.name, [])
        repo["bazel_dep_to_boms"][mod.name] = boms + new_boms

def _process_module_tags(mctx):
    """Process artifact and install tags for a single module."""
    root_module_repos = {}
    non_root_module_repos = {}

    for mod in mctx.modules:
        target_repos = root_module_repos if mod.is_root else non_root_module_repos

        # Process from_toml tags
        for from_toml_tag in mod.tags.from_toml:
            repo = target_repos.get(from_toml_tag.name, {})

            content = mctx.read(mctx.path(from_toml_tag.libs_versions_toml))
            parsed = toml.decode(content)

            (new_artifacts, new_boms) = process_gradle_versions_file(parsed, from_toml_tag.bom_modules)

            _add_artifacts_to_repo(repo, mod, new_artifacts)
            _add_boms_to_repo(repo, mod, new_boms)

            target_repos[from_toml_tag.name] = repo

        for artifact in mod.tags.artifact:
            repo = target_repos.get(artifact.name, {})
            new_artifact = struct(
                group = artifact.group,
                artifact = artifact.artifact,
                version = artifact.version,
                packaging = artifact.packaging,
                classifier = artifact.classifier,
                force_version = artifact.force_version,
                neverlink = artifact.neverlink,
                testonly = artifact.testonly,
                exclusions = _add_exclusions(artifact.exclusions),
            )
            _add_artifacts_to_repo(repo, mod, [new_artifact])

            target_repos[artifact.name] = repo

        for install in mod.tags.install:
            repo = target_repos.get(install.name, {})

            repo["resolver"] = install.resolver

            _add_artifacts_to_repo(repo, mod, [unpack_coordinates(a) for a in install.artifacts])
            _add_boms_to_repo(repo, mod, [unpack_coordinates(b) for b in install.boms])

            existing_repos = repo.get("repositories", [])
            for repository in parse.parse_repository_spec_list(install.repositories):
                repo_string = _json.write_repository_spec(repository)
                if repo_string not in existing_repos:
                    existing_repos.append(repo_string)
            repo["repositories"] = existing_repos

            repo["excluded_artifacts"] = repo.get("excluded_artifacts", []) + install.excluded_artifacts

            _logical_or(repo, "fetch_sources", False, install.fetch_sources)
            _logical_or(repo, "generate_compat_repositories", False, install.generate_compat_repositories)
            _logical_or(repo, "use_starlark_android_rules", False, install.use_starlark_android_rules)
            _logical_or(repo, "ignore_empty_files", False, install.ignore_empty_files)
            _logical_or(repo, "use_credentials_from_home_netrc_file", False, install.use_credentials_from_home_netrc_file)

            repo["version_conflict_policy"] = _fail_if_different(
                "version_conflict_policy",
                repo.get("version_conflict_policy"),
                install.version_conflict_policy,
                [None, "default"],
            )

            repo["strict_visibility_value"] = _fail_if_different(
                "strict_visibility_value",
                repo.get("strict_visibility_value", []),
                install.strict_visibility_value,
                [None, []],
            )

            additional_netrc_lines = repo.get("additional_netrc_lines", []) + getattr(install, "additional_netrc_lines", [])
            repo["additional_netrc_lines"] = additional_netrc_lines

            repo["aar_import_bzl_label"] = _fail_if_different(
                "aar_import_bzl_label",
                repo.get("aar_import_bzl_label"),
                install.aar_import_bzl_label,
                [DEFAULT_AAR_IMPORT_LABEL, None],
            )

            repo["duplicate_version_warning"] = _fail_if_different(
                "duplicate_version_warning",
                repo.get("duplicate_version_warning"),
                install.duplicate_version_warning,
                [None, "warn"],
            )

            # Get the longest timeout
            timeout = repo.get("resolve_timeout", install.resolve_timeout)
            if install.resolve_timeout > timeout:
                timeout = install.resolve_timeout
            repo["resolve_timeout"] = timeout

            if mod.is_root:
                repo["repin_instructions"] = install.repin_instructions
                repo["known_contributing_modules"] = sets.make(install.known_contributing_modules)

            repo["additional_coursier_options"] = repo.get("additional_coursier_options", []) + getattr(install, "additional_coursier_options", [])

            target_repos[install.name] = repo

        # Process amend_artifact tags
        for amend in mod.tags.amend_artifact:
            repo = target_repos.get(amend.name, {})
            if mod.is_root:
                artifacts = repo.get("artifacts", [])
                boms = repo.get("boms", [])
            else:
                if not "bazel_dep_to_artifacts" in repo:
                    repo["bazel_dep_to_artifacts"] = {}
                artifacts = repo["bazel_dep_to_artifacts"].get(mod.name, [])
                if not "bazel_dep_to_boms" in repo:
                    repo["bazel_dep_to_boms"] = {}
                boms = repo["bazel_dep_to_boms"].get(mod.name, [])

            # Amend matching artifacts and BOMs.
            if not apply_amendment(amend, artifacts, boms):
                # If no matching artifact found, this might be an error or we could create a placeholder
                fail("No artifact found matching coordinates '%s' for amendment" % amend.coordinates)

    return root_module_repos, non_root_module_repos

def _merge_repo_lists(root_list, non_root_list):
    """Merge two lists, removing duplicates while preserving order, root items first."""
    seen = []
    merged_list = []
    for item in root_list + non_root_list:
        if item not in seen:
            merged_list.append(item)
            seen.append(item)
    return merged_list

def concat_coursier_options(root_list, non_root_list):
    """Concatenate coursier options (root first), never deduplicating.

    Unlike the other repo list attributes, `additional_coursier_options` is an
    ordered argument vector passed verbatim to coursier, not a set. It can
    legitimately contain repeated identical tokens (e.g. coursier's repeatable
    `--variant` flag), so deduplicating it would corrupt the argument list.
    """
    return root_list + non_root_list

def _print_layering_diagnostics(diagnostics, repin_env_var, rje_verbose_env_var):
    for diagnostic in diagnostics:
        if should_print_diagnostic(diagnostic, repin_env_var, rje_verbose_env_var):
            print(diagnostic.text)

def maven_impl(mctx):
    repos = {}
    overrides = {}
    override_visibilities = {}
    http_files = []
    compat_repos = []

    # Process overrides first (they don't need deduplication)
    # The order of the transitive overrides do not matter, but the root
    # overrides take precedence over all transitive ones.
    for idx, mod in enumerate(reversed(mctx.modules)):
        # Rotate the root module to the last to be visited.
        is_root_module = idx == (len(mctx.modules) - 1)
        for override in mod.tags.override:
            if not override.name in overrides:
                overrides[override.name] = {}
            if not override.name in override_visibilities:
                override_visibilities[override.name] = {}
            value = str(override.target)
            if is_root_module:
                # Allow the root module's overrides to take precedence over any transitive overrides.
                to_use = value
                visibility_to_use = override.visibility
            else:
                current = overrides[override.name].get(override.coordinates)
                to_use = _fail_if_different("Target of override for %s" % override.coordinates, current, value, [None])

                current_visibility = override_visibilities[override.name].get(override.coordinates)
                if current_visibility == None:
                    visibility_to_use = override.visibility
                else:
                    visibility_to_use = _fail_if_different("Visibility of override for %s" % override.coordinates, current_visibility, override.visibility, [[]])

            overrides[override.name].update({override.coordinates: to_use})
            override_visibilities[override.name].update({override.coordinates: visibility_to_use})

    # First pass: process the module tags, separating root and non-root modules
    root_module_repos, non_root_module_repos = _process_module_tags(mctx)

    # Second pass: merge and deduplicate repositories
    all_repo_names = {name: True for name in root_module_repos.keys() + non_root_module_repos.keys()}.keys()

    repin_env_var = mctx.getenv("REPIN") or mctx.getenv("RULES_JVM_EXTERNAL_REPIN")
    rje_verbose_env_var = mctx.getenv("RJE_VERBOSE")

    for repo_name in all_repo_names:
        root_repo = root_module_repos.get(repo_name, {})
        non_root_repo = non_root_module_repos.get(repo_name, {})

        # Start with non-root repo as base, then override with root repo settings
        merged_repo = {}
        merged_repo.update(non_root_repo)
        merged_repo.update(root_repo)

        layered_artifacts_and_boms = layer_maven_namespace(
            name = repo_name,
            root_present = repo_name in root_module_repos,
            root_artifacts = root_repo.get("artifacts", []),
            root_boms = root_repo.get("boms", []),
            resolver = root_repo.get("resolver", _DEFAULT_RESOLVER),
            version_conflict_policy = root_repo.get("version_conflict_policy", "default"),
            duplicate_version_warning = root_repo.get("duplicate_version_warning") or "warn",
            known_contributing_modules = root_repo.get("known_contributing_modules", sets.make()),
            bazel_dep_to_non_root_artifacts = non_root_repo.get("bazel_dep_to_artifacts", {}),
            bazel_dep_to_non_root_boms = non_root_repo.get("bazel_dep_to_boms", {}),
        )
        merged_repo["artifacts"] = layered_artifacts_and_boms.artifacts
        merged_repo["boms"] = layered_artifacts_and_boms.boms
        _print_layering_diagnostics(layered_artifacts_and_boms.diagnostics, repin_env_var, rje_verbose_env_var)

        # For list attributes, concatenate but avoid duplicates (root items first)
        for list_attr in ["repositories", "excluded_artifacts", "additional_netrc_lines"]:
            root_list = root_repo.get(list_attr, [])
            non_root_list = non_root_repo.get(list_attr, [])
            merged_repo[list_attr] = _merge_repo_lists(root_list, non_root_list)

        merged_repo["additional_coursier_options"] = concat_coursier_options(
            root_repo.get("additional_coursier_options", []),
            non_root_repo.get("additional_coursier_options", []),
        )

        repos[repo_name] = merged_repo

    # Breaking out the logic for picking lock files, because it's not terribly simple
    repo_to_lock_file = {}
    for mod in mctx.modules:
        for install in mod.tags.install:
            if install.lock_file:
                entries = repo_to_lock_file.get(install.name, [])
                if not install.lock_file in entries:
                    entries.append(install.lock_file)
                repo_to_lock_file[install.name] = entries

    # The root module always wins when it comes to these values
    for mod in mctx.modules:
        if mod.is_root:
            for install in mod.tags.install:
                repo = repos[install.name]

                # We will always have a lock file, so this is fine
                repo_to_lock_file[install.name] = [install.lock_file]
                repo["dependency_index"] = install.index_file
                repo["fail_if_repin_required"] = install.fail_if_repin_required
                repo["fail_on_missing_checksum"] = install.fail_on_missing_checksum
                repo["fetch_javadoc"] = install.fetch_javadoc
                repo["fetch_sources"] = install.fetch_sources
                repo["resolver"] = install.resolver
                repo["resolver_extra_dependencies"] = install.resolver_extra_dependencies
                repo["strict_visibility"] = install.strict_visibility
                if len(install.repositories):
                    mapped_repos = []
                    for repository in parse.parse_repository_spec_list(install.repositories):
                        repo_string = _json.write_repository_spec(repository)
                        if repo_string not in mapped_repos:
                            mapped_repos.append(repo_string)
                    repo["repositories"] = mapped_repos

                repos[install.name] = repo

    # There should be at most one lock file per `name`
    for repo_name, lock_files in repo_to_lock_file.items():
        if len(lock_files) > 1:
            fail("There can only be one lock file for the repo %s. Lock files seen were %s" % (
                repo_name,
                ", ".join(lock_files),
            ))
        repos.get(repo_name)["lock_file"] = lock_files[0]

    existing_repos = []
    for (name, repo) in repos.items():
        boms_json = [json.encode(remove_empty_fields(b)) for b in repo.get("boms", [])]
        artifacts_json = [json.encode(remove_empty_fields(a)) for a in repo.get("artifacts", [])]

        excluded_artifacts = parse.parse_exclusion_spec_list(repo.get("excluded_artifacts", []))
        excluded_artifacts_json = [_json.write_exclusion_spec(a) for a in excluded_artifacts]

        if len(repo.get("repositories", [])) == 0:
            existing_repos = []
            for repository in parse.parse_repository_spec_list(DEFAULT_REPOSITORIES):
                repo_string = _json.write_repository_spec(repository)
                if repo_string not in existing_repos:
                    existing_repos.append(repo_string)
            repo["repositories"] = existing_repos

        if repo.get("resolver", _DEFAULT_RESOLVER) == "coursier":
            coursier_fetch(
                # Name this repository "unpinned_{name}" if the user specified a
                # maven_install.json file. The actual @{name} repository will be
                # created from the maven_install.json file in the coursier_fetch
                # invocation after this.
                name = "unpinned_" + name if repo.get("lock_file") else name,
                pinned_repo_name = name if repo.get("lock_file") else None,
                user_provided_name = name,
                repositories = repo.get("repositories"),
                artifacts = artifacts_json,
                boms = boms_json,
                fail_on_missing_checksum = repo.get("fail_on_missing_checksum"),
                fetch_sources = repo.get("fetch_sources"),
                fetch_javadoc = repo.get("fetch_javadoc"),
                excluded_artifacts = excluded_artifacts_json,
                generate_compat_repositories = False,
                version_conflict_policy = repo.get("version_conflict_policy"),
                override_targets = overrides.get(name),
                override_target_visibilities = override_visibilities.get(name, {}),
                strict_visibility = repo.get("strict_visibility"),
                strict_visibility_value = repo.get("strict_visibility_value"),
                use_credentials_from_home_netrc_file = repo.get("use_credentials_from_home_netrc_file"),
                maven_install_json = repo.get("lock_file"),
                dependency_index = repo.get("dependency_index"),
                resolve_timeout = repo.get("resolve_timeout"),
                use_starlark_android_rules = repo.get("use_starlark_android_rules"),
                aar_import_bzl_label = repo.get("aar_import_bzl_label"),
                duplicate_version_warning = repo.get("duplicate_version_warning"),
                ignore_empty_files = repo.get("ignore_empty_files"),
                additional_coursier_options = repo.get("additional_coursier_options"),
            )
        else:
            workspace_prefix = "@@"

            # Only the coursier resolver allows the lock file to be omitted.
            unpinned_maven_pin_command_alias(
                name = "unpinned_" + name,
                alias = "%s%s//:pin" % (workspace_prefix, name),
            )

        if repo.get("generate_compat_repositories"):
            seen = _generate_compat_repos(name, compat_repos, repo.get("artifacts", []))
            compat_repos.extend(seen)

        if repo.get("lock_file"):
            lock_file_content = mctx.read(mctx.path(repo.get("lock_file")))

            if not len(lock_file_content) or contains_git_conflict_markers(repo["lock_file"], lock_file_content):
                lock_file = {
                    "artifacts": {},
                    "dependencies": {},
                    "repositories": {},
                    "version": "3",
                }
            else:
                lock_file = json.decode(lock_file_content)

            if v3_lock_file.is_valid_lock_file(lock_file):
                artifacts = v3_lock_file.get_artifacts(lock_file)
                importer = v3_lock_file
            elif v2_lock_file.is_valid_lock_file(lock_file):
                artifacts = v2_lock_file.get_artifacts(lock_file)
                importer = v2_lock_file
            elif v1_lock_file.is_valid_lock_file(lock_file):
                artifacts = v1_lock_file.get_artifacts(lock_file)
                importer = v1_lock_file
            else:
                fail("Unable to determine lock file version: %s" % repo.get("lock_file"))

            created = download_pinned_deps(mctx = mctx, artifacts = artifacts, http_files = http_files, has_m2local = importer.has_m2local(lock_file))
            existing_repos.extend(created)

            pinned_coursier_fetch(
                name = name,
                user_provided_name = name,
                repositories = repo.get("repositories"),
                boms = boms_json,
                artifacts = artifacts_json,
                fetch_sources = repo.get("fetch_sources"),
                fetch_javadoc = repo.get("fetch_javadoc"),
                resolver = repo.get("resolver", _DEFAULT_RESOLVER),
                resolver_extra_dependencies = repo.get("resolver_extra_dependencies", []),
                generate_compat_repositories = False,
                maven_install_json = repo.get("lock_file"),
                dependency_index = repo.get("dependency_index"),
                override_targets = overrides.get(name),
                override_target_visibilities = override_visibilities.get(name, {}),
                strict_visibility = repo.get("strict_visibility"),
                strict_visibility_value = repo.get("strict_visibility_value"),
                additional_netrc_lines = repo.get("additional_netrc_lines"),
                use_credentials_from_home_netrc_file = repo.get("use_credentials_from_home_netrc_file"),
                fail_if_repin_required = repo.get("fail_if_repin_required"),
                use_starlark_android_rules = repo.get("use_starlark_android_rules"),
                aar_import_bzl_label = repo.get("aar_import_bzl_label"),
                duplicate_version_warning = repo.get("duplicate_version_warning"),
                excluded_artifacts = excluded_artifacts_json,
                repin_instructions = repo.get("repin_instructions"),
            )

            if repo.get("generate_compat_repositories"):
                # Convert lock file artifacts (which are dicts) to structs
                lock_file_artifacts = [unpack_coordinates(a["coordinates"]) for a in artifacts]
                seen = _generate_compat_repos(name, compat_repos, lock_file_artifacts)
                compat_repos.extend(seen)

    if bazel_features.external_deps.extension_metadata_has_reproducible:
        # The type and attributes of repositories created by this extension are fully deterministic
        # and thus don't need to be included in MODULE.bazel.lock.
        # Note: This ignores get_m2local_url, but that depends on local information and environment
        # variables only. In fact, since it depends on the host OS, *not* including the extension
        # result in the lockfile makes it more portable across different machines.
        return mctx.extension_metadata(reproducible = True)
    else:
        return None

maven = module_extension(
    maven_impl,
    tag_classes = {
        "amend_artifact": amend_artifact,
        "artifact": artifact,
        "from_toml": from_toml,
        "install": install,
        "override": override,
    },
    environ = [
        "HOME",
        "REPIN",
        "RULES_JVM_EXTERNAL_REPIN",
        "RJE_VERBOSE",
        "USERPROFILE",
    ],
)
