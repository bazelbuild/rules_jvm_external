package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import com.google.common.collect.ImmutableList;
import java.util.LinkedList;
import java.util.List;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;

class ErrorReportingListener extends AbstractRepositoryListener {

  private final List<Exception> exceptions = new LinkedList<>();

  @Override
  public void artifactDescriptorInvalid(RepositoryEvent event) {
    if (event.getException() != null) {
      exceptions.add(event.getException());
    }
  }

  @Override
  public void artifactDescriptorMissing(RepositoryEvent event) {
    if (event.getException() != null) {
      exceptions.add(event.getException());
    }
  }

  @Override
  public void metadataInvalid(RepositoryEvent event) {
    if (event.getException() != null) {
      exceptions.add(event.getException());
    }
  }

  public List<Exception> getExceptions() {
    return ImmutableList.copyOf(exceptions);
  }
}
