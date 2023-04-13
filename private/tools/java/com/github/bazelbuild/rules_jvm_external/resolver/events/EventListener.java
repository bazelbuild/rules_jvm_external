package com.github.bazelbuild.rules_jvm_external.resolver.events;

import java.io.Closeable;

public interface EventListener extends Closeable {

  void onEvent(Event event);

  @Override
  void close();
}
