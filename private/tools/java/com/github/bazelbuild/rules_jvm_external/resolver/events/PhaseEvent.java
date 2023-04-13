package com.github.bazelbuild.rules_jvm_external.resolver.events;

public class PhaseEvent implements Event {

  private final String name;

  public PhaseEvent(String name) {
    this.name = name;
  }

  public String getPhaseName() {
    return name;
  }
}
