// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.github.bazelbuild.rules_jvm_external.resolver.remote.gcs;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.util.List;

/**
 * Provides OAuth 2.0 access tokens for Google Cloud Storage using
 * Google's official auth library with Application Default Credentials (ADC).
 *
 * <p>Uses {@link GoogleCredentials#getApplicationDefault()} which reads
 * ADC from the standard locations (the same credentials used by
 * {@code gcloud auth application-default login}).
 */
public class GcpTokenProvider {

  private static final List<String> SCOPES =
      List.of("https://www.googleapis.com/auth/devstorage.read_only");

  private final GoogleCredentials credentials;

  public GcpTokenProvider() {
    this(createDefaultCredentials());
  }

  GcpTokenProvider(GoogleCredentials credentials) {
    this.credentials = credentials;
  }

  private static GoogleCredentials createDefaultCredentials() {
    try {
      return GoogleCredentials.getApplicationDefault().createScoped(SCOPES);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load Google Application Default Credentials", e);
    }
  }

  /**
   * Returns a valid access token, refreshing automatically when it expires.
   */
  public String getAccessToken() {
    try {
      credentials.refreshIfExpired();
      return credentials.getAccessToken().getTokenValue();
    } catch (IOException e) {
      throw new RuntimeException("Failed to refresh GCS access token", e);
    }
  }
}
