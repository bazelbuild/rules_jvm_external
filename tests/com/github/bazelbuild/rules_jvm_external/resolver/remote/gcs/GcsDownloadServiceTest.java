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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.bazelbuild.rules_jvm_external.resolver.DownloadService;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class GcsDownloadServiceTest {

  @Test
  public void delegatesNonGcsGetToDefaultService() {
    var defaultService = new RecordingDownloadService();
    GcsDownloadService service = new GcsDownloadService();
    service.initialize(defaultService);

    URI httpUri = URI.create("https://repo1.maven.org/maven2/com/example/foo/1.0/foo-1.0.jar");
    service.get(httpUri);

    assertEquals(httpUri, defaultService.lastGetUri);
    assertNull(defaultService.lastHeadUri);
  }

  @Test
  public void delegatesNonGcsHeadToDefaultService() {
    var defaultService = new RecordingDownloadService();
    GcsDownloadService service = new GcsDownloadService();
    service.initialize(defaultService);

    URI httpUri = URI.create("https://repo1.maven.org/maven2/com/example/foo/1.0/foo-1.0.jar");
    service.head(httpUri);

    assertEquals(httpUri, defaultService.lastHeadUri);
    assertNull(defaultService.lastGetUri);
  }

  @Test
  public void getFromGcsReturnsDownloadedFile() throws Exception {
    Storage storage = mock(Storage.class);
    BlobId expected = BlobId.of("my-bucket", "artifact.jar");
    when(storage.readAllBytes(expected)).thenReturn("hello-gcs".getBytes());

    GcsDownloadService service = withStorage(storage);
    service.initialize(new RecordingDownloadService());

    Path result = service.get(URI.create("gcs://my-bucket/artifact.jar"));

    assertTrue(result != null);
    assertTrue(Files.exists(result));
    assertEquals("hello-gcs", Files.readString(result));
  }

  @Test
  public void getFromGcsReturnsNullWhenBlobMissing() {
    // A 404 means the artifact is not in this repository; get() must return null so the resolver
    // can fall through to the next repository, not abort the whole resolution.
    Storage storage = mock(Storage.class);
    when(storage.readAllBytes(BlobId.of("my-bucket", "missing.jar")))
        .thenThrow(new StorageException(404, "Not Found"));

    GcsDownloadService service = withStorage(storage);
    service.initialize(new RecordingDownloadService());

    assertNull(service.get(URI.create("gcs://my-bucket/missing.jar")));
  }

  @Test(expected = StorageException.class)
  public void getFromGcsRethrowsUnexpectedStorageErrors() {
    // Non-404 failures (auth, network, server errors) are real and must surface, not be swallowed
    // as a missing artifact.
    Storage storage = mock(Storage.class);
    when(storage.readAllBytes(BlobId.of("my-bucket", "boom.jar")))
        .thenThrow(new StorageException(500, "Internal Server Error"));

    GcsDownloadService service = withStorage(storage);
    service.initialize(new RecordingDownloadService());

    service.get(URI.create("gcs://my-bucket/boom.jar"));
  }

  @Test
  public void headGcsReturnsTrueWhenBlobExists() {
    Storage storage = mock(Storage.class);
    when(storage.get(BlobId.of("my-bucket", "artifact.jar"))).thenReturn(mock(Blob.class));

    GcsDownloadService service = withStorage(storage);
    service.initialize(new RecordingDownloadService());

    assertTrue(service.head(URI.create("gcs://my-bucket/artifact.jar")));
  }

  @Test
  public void headGcsReturnsFalseWhenBlobMissing() {
    Storage storage = mock(Storage.class);
    when(storage.get(BlobId.of("my-bucket", "missing.jar"))).thenReturn(null);

    GcsDownloadService service = withStorage(storage);
    service.initialize(new RecordingDownloadService());

    assertFalse(service.head(URI.create("gcs://my-bucket/missing.jar")));
  }

  @Test
  public void toBlobIdExtractsBucketAndPath() {
    BlobId blobId = GcsDownloadService.toBlobId(
        URI.create("gcs://repo.revolut.com/snapshots/com/example/foo/1.0/foo-1.0.jar"));

    assertEquals("repo.revolut.com", blobId.getBucket());
    assertEquals("snapshots/com/example/foo/1.0/foo-1.0.jar", blobId.getName());
  }

  private static GcsDownloadService withStorage(Storage storage) {
    return new GcsDownloadService() {
      @Override
      protected Storage createStorage() {
        return storage;
      }
    };
  }

  private static class RecordingDownloadService implements DownloadService {
    URI lastGetUri;
    URI lastHeadUri;

    @Override
    public Path get(URI uri) {
      lastGetUri = uri;
      return null;
    }

    @Override
    public boolean head(URI uri) {
      lastHeadUri = uri;
      return false;
    }
  }
}
