package com.github.bazelbuild.rules_jvm_external.resolver.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import javax.net.ssl.SSLSession;

public class EmptyResponse<X> implements HttpResponse<X> {
  private final HttpRequest request;
  private final int statusCode;

  public EmptyResponse(HttpRequest request, int statusCode) {
    this.request = request;
    this.statusCode = statusCode;
  }

  @Override
  public int statusCode() {
    return statusCode;
  }

  @Override
  public HttpRequest request() {
    return request;
  }

  @Override
  public Optional<HttpResponse<X>> previousResponse() {
    return Optional.empty();
  }

  @Override
  public HttpHeaders headers() {
    return null;
  }

  @Override
  public X body() {
    return null;
  }

  @Override
  public Optional<SSLSession> sslSession() {
    return Optional.empty();
  }

  @Override
  public URI uri() {
    return request.uri();
  }

  @Override
  public HttpClient.Version version() {
    return HttpClient.Version.HTTP_1_1;
  }
}
