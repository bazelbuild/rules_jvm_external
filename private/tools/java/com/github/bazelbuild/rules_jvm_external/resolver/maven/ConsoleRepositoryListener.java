package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import static com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent.Stage.COMPLETE;
import static com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent.Stage.STARTING;

import com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.events.PhaseEvent;
import java.io.Closeable;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;

class ConsoleRepositoryListener extends AbstractRepositoryListener implements Closeable {

  private final EventListener listener;

  public ConsoleRepositoryListener(EventListener listener) {
    this.listener = listener;
  }

  @Override
  public void artifactDownloading(RepositoryEvent event) {
    listener.onEvent(new DownloadEvent(STARTING, MavenCoordinates.asString(event.getArtifact())));
  }

  @Override
  public void artifactDownloaded(RepositoryEvent event) {
    listener.onEvent(new DownloadEvent(COMPLETE, MavenCoordinates.asString(event.getArtifact())));
  }

  public void setPhase(String phaseDescription) {
    listener.onEvent(new PhaseEvent(phaseDescription));
  }

  @Override
  public void close() {
    listener.close();
  }
}
