package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Exclusion;

class MavenCoordinates {

  public static String asString(Exclusion exclusion) {
    return construct(
        exclusion.getGroupId(),
        exclusion.getArtifactId(),
        exclusion.getExtension(),
        exclusion.getClassifier(),
        null);
  }

  public static String asString(Artifact artifact) {
    return construct(
        artifact.getGroupId(),
        artifact.getArtifactId(),
        artifact.getExtension(),
        artifact.getClassifier(),
        artifact.getVersion());
  }

  private static String construct(
      String groupId, String artifactId, String extension, String classifier, String version) {
    StringBuilder coords = new StringBuilder();
    coords.append(groupId).append(":").append(artifactId);

    if (extension != null && !extension.isEmpty()) {
      coords.append(":").append(extension);
    }

    if (classifier != null && !classifier.isEmpty() && !"jar".equals(classifier)) {
      coords.append(":").append(classifier);
    }

    if (version != null && !version.isEmpty()) {
      coords.append(":").append(version);
    }

    return coords.toString();
  }
}
