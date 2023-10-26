package com.github.bazelbuild.rules_jvm_external.resolver.cmd;

import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionRequest;
import com.github.bazelbuild.rules_jvm_external.resolver.Resolver;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.events.PhaseEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.maven.MavenResolver;
import com.github.bazelbuild.rules_jvm_external.resolver.netrc.Netrc;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {

  private final ResolutionRequest request;
  private final Resolver resolver;
  private final boolean fetchSources;
  private final boolean fetchJavadoc;
  private final Netrc netrc;
  private final Path output;
  private final String inputHash;

  public Config(EventListener listener, String... args) throws IOException {
    Path configPath = null;
    this.netrc = Netrc.fromUserHome();

    ResolutionRequest request = new ResolutionRequest();
    Resolver resolver = new MavenResolver(netrc, listener);
    boolean fetchSources = false;
    boolean fetchJavadoc = false;
    Path output = null;
    String inputHash = null;

    String envUseUnsafeCache = System.getenv("RJE_UNSAFE_CACHE");
    if (envUseUnsafeCache != null) {
      if ("1".equals(envUseUnsafeCache) || Boolean.parseBoolean(envUseUnsafeCache)) {
        request.useUnsafeSharedCache(true);
      }
    }

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--argsfile":
          i++;
          configPath = Paths.get(args[i]);
          break;

        case "--bom":
          i++;
          request.addBom(args[i]);
          break;

        case "--input_hash":
          i++;
          inputHash = args[i];
          break;

        case "--javadocs":
          fetchJavadoc = true;
          break;

        case "--output":
          i++;
          String bazelWorkspaceDir = System.getenv("BUILD_WORKSPACE_DIRECTORY");
          if (bazelWorkspaceDir == null) {
            output = Paths.get(args[i]);
          } else {
            output = Paths.get(bazelWorkspaceDir).resolve(args[i]);
          }
          break;

        case "--resolver":
          i++;
          switch (args[i]) {
            case "maven":
              resolver = new MavenResolver(netrc, listener);
              break;

            default:
              throw new IllegalArgumentException("Resolver must be one of `maven` or `gradle`");
          }
          break;

        case "--sources":
          fetchSources = true;
          break;

        case "--repository":
          i++;
          request.addRepository(args[i]);
          break;

        case "--use_unsafe_shared_cache":
          request.useUnsafeSharedCache(true);
          break;

        default:
          request.addArtifact(args[i]);
          break;
      }
    }

    if (configPath != null) {
      listener.onEvent(new PhaseEvent("Reading parameter file"));
      String rawJson = Files.readString(configPath);
      ExternalConfig config =
          new Gson().fromJson(rawJson, new TypeToken<ExternalConfig>() {}.getType());

      fetchJavadoc |= config.isFetchJavadoc();
      fetchSources |= config.isFetchSources();

      request.useUnsafeSharedCache(
          request.isUseUnsafeSharedCache() || config.isUsingUnsafeSharedCache());

      config.getRepositories().forEach(request::addRepository);

      config.getGlobalExclusions().forEach(request::exclude);

      config
          .getBoms()
          .forEach(
              art -> {
                StringBuilder coords =
                    new StringBuilder()
                        .append(art.getGroupId())
                        .append(":")
                        .append(art.getArtifactId())
                        .append(":")
                        .append("pom")
                        .append(":")
                        .append(art.getVersion());
                request.addBom(
                    coords.toString(),
                    art.getExclusions().stream()
                        .map(c -> c.getGroupId() + ":" + c.getArtifactId())
                        .toArray(String[]::new));
              });

      config
          .getArtifacts()
          .forEach(
              art -> {
                StringBuilder coords = new StringBuilder();
                coords.append(art.getGroupId()).append(":").append(art.getArtifactId());
                if (art.getExtension() != null) {
                  coords.append(":").append(art.getExtension());
                }
                if (art.getClassifier() != null) {
                  coords.append(":").append(art.getClassifier());
                }
                coords.append(":").append(art.getVersion());
                request.addArtifact(
                    coords.toString(),
                    art.getExclusions().stream()
                        .map(c -> c.getGroupId() + ":" + c.getArtifactId())
                        .toArray(String[]::new));
              });
    }

    if (request.getRepositories().isEmpty()) {
      request.addRepository("https://repo1.maven.org/maven2/");
    }

    this.request = request;
    this.resolver = resolver;
    this.fetchSources = fetchSources;
    this.fetchJavadoc = fetchJavadoc;
    this.inputHash = inputHash;

    this.output = output;
  }

  public ResolutionRequest getResolutionRequest() {
    return request;
  }

  public Resolver getResolver() {
    return resolver;
  }

  public boolean isFetchSources() {
    return fetchSources;
  }

  public boolean isFetchJavadoc() {
    return fetchJavadoc;
  }

  public Netrc getNetrc() {
    return netrc;
  }

  public String getInputHash() {
    return inputHash;
  }

  public Path getOutput() {
    return output;
  }
}
