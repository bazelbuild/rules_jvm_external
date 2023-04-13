package com.github.bazelbuild.rules_jvm_external.resolver.ui;

import static com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent.Stage.STARTING;

import com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.events.Event;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.events.PhaseEvent;
import java.util.Locale;

public class PlainConsoleListener implements EventListener {
  @Override
  public void onEvent(Event event) {
    if (event instanceof PhaseEvent) {
      System.err.println(
          "Currently: " + ((PhaseEvent) event).getPhaseName().toLowerCase(Locale.ENGLISH));
    }

    if (event instanceof DownloadEvent) {
      DownloadEvent de = (DownloadEvent) event;
      if (de.getStage() == STARTING) {
        System.err.println("Downloading: " + de.getTarget());
      }
    }
  }

  @Override
  public void close() {}
}
