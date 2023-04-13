package com.github.bazelbuild.rules_jvm_external.resolver;

public class UnresolvedArtifactException extends RuntimeException {
  public UnresolvedArtifactException(Throwable e) {
    super(e);
  }
}
