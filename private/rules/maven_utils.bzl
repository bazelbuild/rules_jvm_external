load("//private/lib:bzlmod.bzl", "get_module_name_of_owner_of_repo")
load("//private/lib:coordinates.bzl", _unpack_coordinates = "unpack_coordinates")

def unpack_coordinates(coords):
    print("Please load `unpack_coordinates` from `@rules_jvm_external//private/lib:coordinates.bzl`.")
    return _unpack_coordinates(coords)

def _whitespace(indent):
    whitespace = ""
    for i in range(indent):
        whitespace = whitespace + " "
    return whitespace

def format_dep(unpacked, indent = 8, include_version = True):
    whitespace = _whitespace(indent)

    dependency = [
        whitespace,
        "<dependency>\n",
        whitespace,
        "    <groupId>%s</groupId>\n" % unpacked.groupId,
        whitespace,
        "    <artifactId>%s</artifactId>\n" % unpacked.artifactId,
    ]

    if include_version:
        dependency.extend([
            whitespace,
            "    <version>%s</version>\n" % unpacked.version,
        ])

    if unpacked.type and unpacked.type != "jar":
        dependency.extend([
            whitespace,
            "    <type>%s</type>\n" % unpacked.type,
        ])

    if unpacked.scope and unpacked.scope != "compile":
        dependency.extend([
            whitespace,
            "    <scope>%s</scope>\n" % unpacked.scope,
        ])

    if unpacked.classifier:
        dependency.extend([
            whitespace,
            "    <classifier>%s</classifier>\n" % unpacked.classifier,
        ])

    dependency.extend([
        whitespace,
        "</dependency>",
    ])

    return "".join(dependency)

def generate_pom(
        ctx,
        coordinates,
        pom_template,
        out_name,
        parent = None,
        versioned_dep_coordinates = [],
        unversioned_dep_coordinates = [],
        runtime_deps = [],
        indent = 8):
    unpacked_coordinates = _unpack_coordinates(coordinates)
    substitutions = {
        "{groupId}": unpacked_coordinates.groupId,
        "{artifactId}": unpacked_coordinates.artifactId,
        "{version}": unpacked_coordinates.version,
        "{type}": unpacked_coordinates.type or "jar",
        "{classifier}": unpacked_coordinates.classifier or "",
        "{scope}": unpacked_coordinates.scope or "compile",
    }

    if parent:
        # We only want the groupId, artifactID, and version
        unpacked_parent = _unpack_coordinates(parent)

        whitespace = _whitespace(indent - 4)
        parts = [
            whitespace,
            "    <groupId>%s</groupId>\n" % unpacked_parent.groupId,
            whitespace,
            "    <artifactId>%s</artifactId>\n" % unpacked_parent.artifactId,
            whitespace,
            "    <version>%s</version>" % unpacked_parent.version,
        ]
        substitutions.update({"{parent}": "".join(parts)})

    deps = []
    for dep in sorted(versioned_dep_coordinates) + sorted(unversioned_dep_coordinates):
        include_version = dep in versioned_dep_coordinates
        split = dep.split(":")
        if len(split) == 5 and split[3] not in ["import", "compile", "runtime", "test", "provided", "system"]:
            print(split)
            gradle_format = split[0] + ":" + split[1] + ":" + split[4] + ":" + split[3] + "@" + split[2]
            unpacked = _unpack_coordinates(gradle_format)
        else:
            unpacked = _unpack_coordinates(dep)
        new_scope = "runtime" if dep in runtime_deps else unpacked.scope
        unpacked = struct(
            groupId = unpacked.groupId,
            artifactId = unpacked.artifactId,
            type = unpacked.type,
            scope = new_scope,
            classifier = unpacked.classifier,
            version = unpacked.version,
        )
        deps.append(format_dep(unpacked, indent = indent, include_version = include_version))

    substitutions.update({"{dependencies}": "\n".join(deps)})

    out = ctx.actions.declare_file("%s" % out_name)
    ctx.actions.expand_template(
        template = pom_template,
        output = out,
        substitutions = substitutions,
    )

    return out

def determine_additional_dependencies(jar_files, additional_dependencies):
    """Takes a dict of {`Label`: workspace_name} and returns the `Label`s where any `jar_files match a `workspace_name."""
    to_return = []

    for jar in jar_files:
        owner = jar.owner

        # If we can't tell who the owner is, let's assume things are fine
        if not owner:
            continue

        # Users don't know how `bzlmod` mangles workspace names, but we do
        workspace_name = get_module_name_of_owner_of_repo(owner.workspace_name)

        for (dep, name) in additional_dependencies.items():
            if (name == workspace_name) and dep:
                if not dep in to_return:
                    to_return.append(dep)

    return to_return
