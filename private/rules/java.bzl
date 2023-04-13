load("//private:proxy.bzl", _get_java_proxy_args = "get_java_proxy_args")

def java_path(repository_ctx):
    java_home = repository_ctx.os.environ.get("JAVA_HOME")
    if java_home != None:
        return repository_ctx.path(java_home + "/bin/java")
    elif repository_ctx.which("java") != None:
        return repository_ctx.which("java")
    return None

# Extract the well-known environment variables http_proxy, https_proxy and
# no_proxy and convert them to java.net-compatible property arguments.
def get_java_proxy_args(repository_ctx):
    # Check both lower- and upper-case versions of the environment variables, preferring the former
    http_proxy = repository_ctx.os.environ.get("http_proxy", repository_ctx.os.environ.get("HTTP_PROXY"))
    https_proxy = repository_ctx.os.environ.get("https_proxy", repository_ctx.os.environ.get("HTTPS_PROXY"))
    no_proxy = repository_ctx.os.environ.get("no_proxy", repository_ctx.os.environ.get("NO_PROXY"))
    return _get_java_proxy_args(http_proxy, https_proxy, no_proxy)

# Generate the base command depending on the OS, JAVA_HOME or the
# location of `java`.
def generate_java_jar_command(repository_ctx, jar_path):
    java = java_path(repository_ctx)

    if java != None:
        # https://github.com/coursier/coursier/blob/master/doc/FORMER-README.md#how-can-the-launcher-be-run-on-windows-or-manually-with-the-java-program
        # The -noverify option seems to be required after the proguarding step
        # of the main JAR of coursier.
        cmd = [java, "-noverify", "-jar"] + get_java_proxy_args(repository_ctx) + [jar_path]
    else:
        # Try to execute the jar directly
        cmd = [jar_path] + ["-J%s" % arg for arg in get_java_proxy_args(repository_ctx)]

    return cmd
