package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import java.util.List;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;

class CompoundListener extends AbstractRepositoryListener {
  private final List<RepositoryListener> listeners;

  public CompoundListener(RepositoryListener... listeners) {
    this.listeners = List.of(listeners);
  }

  @Override
  public void artifactDownloading(RepositoryEvent event) {
    listeners.forEach(listener -> listener.artifactDownloading(event));
  }

  @Override
  public void artifactDownloaded(RepositoryEvent event) {
    listeners.forEach(listener -> listener.artifactDownloaded(event));
  }

  @Override
  public void artifactResolving(RepositoryEvent event) {
    listeners.forEach(listener -> listener.artifactResolving(event));
  }

  @Override
  public void artifactResolved(RepositoryEvent event) {
    listeners.forEach(listener -> listener.artifactResolved(event));
  }

  @Override
  public void artifactDescriptorInvalid(RepositoryEvent event) {
    listeners.forEach(listener -> listener.artifactDescriptorInvalid(event));
  }

  @Override
  public void artifactDescriptorMissing(RepositoryEvent event) {
    listeners.forEach(listener -> listener.artifactDescriptorMissing(event));
  }

  @Override
  public void metadataInvalid(RepositoryEvent event) {
    listeners.forEach(listener -> listener.metadataInvalid(event));
  }

  @Override
  public void metadataResolved(RepositoryEvent event) {
    listeners.forEach(listener -> listener.metadataResolved(event));
  }
}
