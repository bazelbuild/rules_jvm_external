package com.github.bazelbuild.rules_jvm_external.resolver.cmd;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class ConfigArtifact {

  // Field names are derived from the values used in `specs.bzl`
  // Getters are named after the equivalent fields in Maven coordinates
  private String group;
  private String artifact;
  private String classifier;
  private String packaging;
  private String version;
  private Set<String> exclusions;

  public String getGroupId() {
    return group;
  }

  public String getArtifactId() {
    return artifact;
  }

  public String getClassifier() {
    return classifier;
  }

  public String getExtension() {
    return packaging;
  }

  public String getVersion() {
    return version;
  }

  public Set<Coordinates> getExclusions() {
    if (exclusions == null) {
      return Set.of();
    }

    return exclusions.stream().map(Coordinates::new).collect(ImmutableSet.toImmutableSet());
  }
}
