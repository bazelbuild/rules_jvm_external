package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import java.util.Map;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;

public class MavenPackagingMappings {

  // A set of known packaging types that are not already handled by the type registry that do not
  // also default to `jar`
  private static final Map<String, ExtensionAndClassifier> PACKAGING_TO_EXTENSION =
      Map.of(
          "aar", new ExtensionAndClassifier("aar", ""),
          "exe", new ExtensionAndClassifier("exe", ""),
          "json", new ExtensionAndClassifier("json", ""));

  private static final ArtifactTypeRegistry TYPE_REGISTRY =
      MavenRepositorySystemUtils.newSession().getArtifactTypeRegistry();
  private static final ExtensionAndClassifier DEFAULT_VALUE =
      new ExtensionAndClassifier("jar", "jar");

  public static String mapPackingToClassifier(String packaging) {
    return getExtensionAndClassifier(packaging).getClassifier();
  }

  public static String mapPackagingToExtension(String packaging) {
    return getExtensionAndClassifier(packaging).getExtension();
  }

  private static ExtensionAndClassifier getExtensionAndClassifier(String packaging) {
    // Special-case handling for aggregating packaging types, where `packaging` is `pom`. We need to
    // do this so that the dependency graph "looks" right and is properly formed.
    // `rules_jvm_external` has mechanisms to compensate for this later.
    if ("pom".equals(packaging)) {
      return DEFAULT_VALUE;
    }

    ArtifactType artifactType = TYPE_REGISTRY.get(packaging);

    if (artifactType != null) {
      return new ExtensionAndClassifier(artifactType.getExtension(), artifactType.getClassifier());
    }

    return PACKAGING_TO_EXTENSION.getOrDefault(packaging, DEFAULT_VALUE);
  }

  public static class ExtensionAndClassifier {
    private final String extension;
    private final String classifier;

    private ExtensionAndClassifier(String extension, String classifier) {
      this.extension = extension;
      this.classifier = classifier;
    }

    public String getExtension() {
      return extension;
    }

    public String getClassifier() {
      return classifier;
    }
  }
}
