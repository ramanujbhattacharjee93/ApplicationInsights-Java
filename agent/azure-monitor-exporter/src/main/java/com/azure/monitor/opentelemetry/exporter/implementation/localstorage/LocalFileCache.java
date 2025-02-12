/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.azure.monitor.opentelemetry.exporter.implementation.localstorage;

import java.io.File;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LocalFileCache {

  private static final Logger logger = LoggerFactory.getLogger(LocalFileCache.class);

  /**
   * Track a list of active filenames persisted on disk. FIFO (First-In-First-Out) read will avoid
   * an additional sorting at every read.
   *
   * <p>There isn't a unique way to identify each java app. C# uses "User@processName" to identify
   * each app, but Java can't rely on process name since it's a system property that can be
   * customized via the command line.
   */
  private final Queue<File> persistedFilesCache = new ConcurrentLinkedDeque<>();

  LocalFileCache(File folder) {
    persistedFilesCache.addAll(loadPersistedFiles(folder));
  }

  // Track the newly persisted filename to the concurrent hashmap.
  void addPersistedFile(File file) {
    persistedFilesCache.add(file);
  }

  File poll() {
    return persistedFilesCache.poll();
  }

  // only used by tests
  Queue<File> getPersistedFilesCache() {
    return persistedFilesCache;
  }

  // load existing files that are not older than 48 hours
  // this will avoid data loss in the case of app crashes and restarts.
  private static List<File> loadPersistedFiles(File folder) {
    return FileUtil.listTrnFiles(folder).stream()
        .sorted(Comparator.comparing(File::lastModified))
        .filter(file -> !isExpired(file, TimeUnit.DAYS.toSeconds(2)))
        .collect(Collectors.toList());
  }

  // files that are older than expiredIntervalSeconds (default 48 hours) are expired
  static boolean isExpired(File file, long expiredIntervalSeconds) {
    String name = file.getName();
    int index = name.indexOf('-');
    if (index == -1) {
      logger.debug("unexpected .trn file name: {}", name);
      return true;
    }
    long timestamp;
    try {
      timestamp = Long.parseLong(name.substring(0, index));
    } catch (NumberFormatException e) {
      logger.debug("unexpected .trn file name: {}", name);
      return true;
    }
    Date expirationDate = new Date(System.currentTimeMillis() - 1000 * expiredIntervalSeconds);
    Date fileDate = new Date(timestamp);
    return fileDate.before(expirationDate);
  }
}
