package com.github.bazelbuild.rules_jvm_external.resolver.ui;

import static com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent.Stage.STARTING;

import com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.events.Event;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.events.PhaseEvent;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.fusesource.jansi.AnsiConsole;

public class AnsiConsoleListener implements EventListener {

  private static final String CLEAR_LINE = "\u001b[2K";
  private static final String MOVE_UP = "\r\u001b[1A";
  private final ScheduledExecutorService executor;
  private String phase = "Starting";
  private final List<String> downloading = new ArrayList<>();
  private final List<String> lastPrinted = new LinkedList<>();

  public AnsiConsoleListener() {
    executor = Executors.newSingleThreadScheduledExecutor();
    executor.scheduleAtFixedRate(this::update, 0, 200, TimeUnit.MILLISECONDS);
  }

  @Override
  public void onEvent(Event event) {
    if (event instanceof PhaseEvent) {
      phase = ((PhaseEvent) event).getPhaseName();
    }

    if (event instanceof DownloadEvent) {
      synchronized (lastPrinted) {
        DownloadEvent de = (DownloadEvent) event;
        if (de.getStage() == STARTING) {
          downloading.add(de.getTarget());
        } else {
          downloading.remove(de.getTarget());
        }
      }
    }
  }

  private void update() {
    synchronized (lastPrinted) {
      clear();

      int width = getConsoleWidth();

      ImmutableList<String> currentDownloads = ImmutableList.copyOf(downloading);

      lastPrinted.add(elideLine(width, "Currently: " + phase.toLowerCase(Locale.ENGLISH)));
      if (!currentDownloads.isEmpty()) {
        lastPrinted.add("Downloading:");
        currentDownloads.forEach(uri -> lastPrinted.add("    " + elideLine(width - 4, uri)));
      }

      AnsiConsole.err().print(String.join("\n", lastPrinted));
    }
  }

  private String elideLine(int maxWidth, String toShorten) {
    int length = toShorten.length();
    if (length < maxWidth) {
      return toShorten;
    }

    int start = length - maxWidth - 3;
    return "..." + toShorten.substring(start);
  }

  private int getConsoleWidth() {
    return AnsiConsole.getTerminalWidth() - 1;
  }

  private void clear() {
    StringBuilder clearing = new StringBuilder();

    for (int i = 0; i < lastPrinted.size() - 1; i++) {
      clearing.append("\r").append(CLEAR_LINE).append(MOVE_UP);
    }
    clearing.append("\r").append(CLEAR_LINE);
    AnsiConsole.err().print(clearing);

    lastPrinted.clear();
  }

  @Override
  public void close() {
    executor.shutdown();
    clear();
  }
}
