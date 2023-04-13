package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.base.Strings;
import java.net.URI;
import java.net.URISyntaxException;

public class MavenRepositoryPath {

  private final String path;

  public MavenRepositoryPath(Coordinates coords) {
    // Matches "[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])"

    StringBuilder path = new StringBuilder();
    path.append(coords.getGroupId().replace('.', '/'))
        .append("/")
        .append(coords.getArtifactId())
        .append("/")
        .append(coords.getVersion())
        .append("/")
        .append(coords.getArtifactId())
        .append("-")
        .append(coords.getVersion());
    if (!Strings.isNullOrEmpty(coords.getClassifier()) && !"jar".equals(coords.getClassifier())) {
      path.append("-").append(coords.getClassifier());
    }
    if (!Strings.isNullOrEmpty(coords.getExtension())) {
      path.append(".").append(coords.getExtension());
    } else {
      path.append(".jar");
    }

    this.path = path.toString();
  }

  public String getPath() {
    return path;
  }

  public URI getUri(URI baseUri) {
    String path = baseUri.getPath();
    if (!path.endsWith("/")) {
      path += "/";
    }
    path += getPath();

    try {
      return new URI(
          baseUri.getScheme(),
          baseUri.getUserInfo(),
          baseUri.getHost(),
          baseUri.getPort(),
          path,
          baseUri.getQuery(),
          baseUri.getFragment());
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
}
