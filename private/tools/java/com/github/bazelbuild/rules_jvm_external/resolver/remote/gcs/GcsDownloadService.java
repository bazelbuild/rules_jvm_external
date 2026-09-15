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

import com.github.bazelbuild.rules_jvm_external.resolver.DownloadService;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link DownloadService} that adds Google Cloud Storage support to rules_jvm_external's
 * Maven resolver.
 *
 * <p>Uses the official Google Cloud Storage client library, which handles ADC authentication,
 * retries, and connection management automatically.
 *
 * <p>Non-GCS URIs are delegated to the default {@link DownloadService}.
 */
public class GcsDownloadService implements DownloadService {

  private DownloadService defaultService;
  private Storage storage;

  @Override
  public void initialize(DownloadService defaultService) {
    this.defaultService = defaultService;
    this.storage = createStorage();
  }

  protected Storage createStorage() {
    return StorageOptions.getDefaultInstance().getService();
  }

  @Override
  public Path get(URI uri) {
    if (!"gcs".equals(uri.getScheme())) {
      return defaultService.get(uri);
    }
    return downloadFromGcs(uri);
  }

  @Override
  public boolean head(URI uri) {
    if (!"gcs".equals(uri.getScheme())) {
      return defaultService.head(uri);
    }
    return headGcs(uri);
  }

  private Path downloadFromGcs(URI gcsUri) {
    BlobId blobId = toBlobId(gcsUri);
    byte[] content;
    try {
      content = storage.readAllBytes(blobId);
    } catch (StorageException e) {
      // A missing object is not an error: the artifact simply is not in this repository, so the
      // resolver must be free to fall through to the next one. Matches the null-on-404 contract of
      // the default HTTP downloader. Any other failure (auth, network) is a real error and rethrown.
      if (e.getCode() == 404) {
        return null;
      }
      throw e;
    }
    try {
      Path tempFile = Files.createTempFile("gcs-resolver", "download");
      Files.write(tempFile, content);
      return tempFile;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private boolean headGcs(URI gcsUri) {
    BlobId blobId = toBlobId(gcsUri);
    return storage.get(blobId) != null;
  }

  static BlobId toBlobId(URI gcsUri) {
    return BlobId.of(gcsUri.getHost(), gcsUri.getPath().substring(1));
  }
}
