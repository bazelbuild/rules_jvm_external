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

package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import com.github.bazelbuild.rules_jvm_external.resolver.DownloadService;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoTransporterException;

/**
 * A custom Eclipse Aether {@link TransporterFactory} that routes all HTTP/HTTPS and file-based
 * downloads requested by the Maven Resolver system through rules_jvm_external's pluggable
 * {@link DownloadService} SPI.
 *
 * This ensures that BOM/POM downloads and resolution lookups utilize our download client,
 * netrc authentication mappings, and pluggable downloader configuration.
 */
public class HttpDownloaderTransporterFactory implements TransporterFactory {
  private final DownloadService downloadService;

  public HttpDownloaderTransporterFactory(DownloadService downloadService) {
    this.downloadService = downloadService;
  }

  @Override
  public float getPriority() {
    return 10.0f;
  }

  @Override
  public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
      throws NoTransporterException {
    String scheme = repository.getProtocol();
    if ("http".equalsIgnoreCase(scheme)
        || "https".equalsIgnoreCase(scheme)
        || "gcs".equalsIgnoreCase(scheme)
        || "file".equalsIgnoreCase(scheme)) {
      return new HttpDownloaderTransporter(downloadService, repository);
    }
    throw new NoTransporterException(repository);
  }


}
