package com.github.bazelbuild.rules_jvm_external.resolver.cmd;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class ExternalConfig {

  private Set<String> repositories;
  private Set<String> globalExclusions;
  private Set<ConfigArtifact> artifacts;
  private Set<String> boms;

  private boolean fetchSources;
  private boolean fetchJavadoc;
  private boolean useUnsafeSharedCache;
  private boolean enableJetify;
  private Set<String> jetify;

  public Set<String> getRepositories() {
    return repositories == null ? ImmutableSet.of() : ImmutableSet.copyOf(repositories);
  }

  public Set<Coordinates> getGlobalExclusions() {
    if (globalExclusions == null) {
      return Set.of();
    }

    return globalExclusions.stream().map(Coordinates::new).collect(ImmutableSet.toImmutableSet());
  }

  public Set<ConfigArtifact> getArtifacts() {
    return artifacts == null ? ImmutableSet.of() : ImmutableSet.copyOf(artifacts);
  }

  public Set<String> getBomCoordinates() {
    return boms == null ? ImmutableSet.of() : ImmutableSet.copyOf(boms);
  }

  public boolean isFetchSources() {
    return fetchSources;
  }

  public boolean isFetchJavadoc() {
    return fetchJavadoc;
  }

  public boolean isUsingUnsafeSharedCache() {
    return useUnsafeSharedCache;
  }

  public boolean isJetifyEnabled() {
    return enableJetify;
  }

  public Set<Coordinates> getJetifyCoordinates() {
    if (jetify == null) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<Coordinates> toReturn = ImmutableSet.builder();
    for (String coordinates : jetify) {
      Coordinates coords = new Coordinates(coordinates);
      if (!Strings.isNullOrEmpty(coords.getVersion())) {
        throw new IllegalStateException("Jetify coordinates must not contain a version");
      }
      toReturn.add(coords);
    }

    return toReturn.build();
  }
}
