// Copyright (C) 2026 GerritForge, Inc.
//
// Licensed under the BSL 1.1 (the "License");
// you may not use this file except in compliance with the License.
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.gerritforge.gerrit.plugins.ai.provider.actions;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

@Singleton
public class ActionFilesCache {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String CACHE_NAME = "action_files";

  record Key(Project.NameKey project, ObjectId revision) {}

  public static CacheModule module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        cache(CACHE_NAME, Key.class, ActionFiles.class)
            .maximumWeight(16 << 20)
            .weigher(FilesWeigher.class)
            .loader(Loader.class);
      }
    };
  }

  private final LoadingCache<Key, ActionFiles> cache;

  @Inject
  ActionFilesCache(@Named(CACHE_NAME) LoadingCache<Key, ActionFiles> cache) {
    this.cache = cache;
  }

  /** Returns the action files of the config revision {@code project} was loaded from. */
  ActionFiles get(ProjectState project) throws IOException {
    Optional<ObjectId> revision = project.getConfig().getRevision();
    if (revision.isEmpty()) {
      return ActionFiles.EMPTY;
    }
    try {
      return cache.get(new Key(project.getNameKey(), revision.get()));
    } catch (ExecutionException e) {
      throw new IOException("Cannot read AI review actions of " + project.getName(), e);
    }
  }

  static class Loader extends CacheLoader<Key, ActionFiles> {
    private final GitRepositoryManager repoManager;
    private final ActionLimits limits;

    @Inject
    Loader(GitRepositoryManager repoManager, ActionLimits limits) {
      this.repoManager = repoManager;
      this.limits = limits;
    }

    @Override
    public ActionFiles load(Key key) throws IOException {
      try (Repository repo = repoManager.openRepository(key.project());
          RevWalk rw = new RevWalk(repo)) {
        ActionFiles files =
            ActionFiles.read(
                rw.getObjectReader(), rw.parseCommit(key.revision()).getTree(), limits);
        logErrors(key, files);
        return files;
      }
    }

    /** Logs each problem once per config revision, not on every request that runs into it. */
    private static void logErrors(Key key, ActionFiles files) {
      files
          .error()
          .ifPresent(
              error ->
                  logger.atWarning().log(
                      "AI review actions of project %s at %s: %s",
                      key.project(), key.revision().name(), error));
      for (ActionFile file : files.byPath().values()) {
        if (file instanceof ActionFile.Invalid invalid) {
          logger.atWarning().log(
              "Invalid AI review action file %s in project %s at %s: %s",
              invalid.path(), key.project(), key.revision().name(), invalid.error());
        }
      }
    }
  }

  static class FilesWeigher implements Weigher<Key, ActionFiles> {
    @Override
    public int weigh(Key key, ActionFiles files) {
      return files.weight();
    }
  }
}
