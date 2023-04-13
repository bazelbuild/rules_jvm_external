package com.github.bazelbuild.rules_jvm_external.resolver.remote;

public class UriNotFoundException extends RuntimeException {
  public UriNotFoundException(String message) {
    super(message);
  }
}
