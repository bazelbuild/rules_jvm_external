package com.github.bazelbuild.rules_jvm_external.resolver.events;

import java.util.Objects;

public class DownloadEvent implements Event {

  private final Stage stage;
  private final String target;

  public DownloadEvent(Stage stage, String target) {
    this.stage = stage;
    this.target = Objects.requireNonNull(target);
  }

  public Stage getStage() {
    return stage;
  }

  public String getTarget() {
    return target;
  }

  public enum Stage {
    STARTING,
    COMPLETE,
  }
}
