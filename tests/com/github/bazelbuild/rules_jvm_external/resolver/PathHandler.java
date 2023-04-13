package com.github.bazelbuild.rules_jvm_external.resolver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class PathHandler implements HttpHandler {

  private final Path base;

  public PathHandler(Path base) {
    this.base = Objects.requireNonNull(base);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();

    // Strip the leading slash off the path if required
    if (path.startsWith("/")) {
      path = path.substring(1);
    }

    Path toServe = base.resolve(path);

    if (!Files.exists(toServe)) {
      exchange.sendResponseHeaders(404, 0);
      exchange.close();
      return;
    }

    byte[] bytes = Files.readAllBytes(toServe);

    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
