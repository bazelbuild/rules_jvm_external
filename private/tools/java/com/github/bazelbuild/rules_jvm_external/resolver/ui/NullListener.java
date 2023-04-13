package com.github.bazelbuild.rules_jvm_external.resolver.ui;

import com.github.bazelbuild.rules_jvm_external.resolver.events.Event;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;

public class NullListener implements EventListener {
  @Override
  public void onEvent(Event event) {
    // Do nothing
  }

  @Override
  public void close() {
    // No nothing
  }
}
