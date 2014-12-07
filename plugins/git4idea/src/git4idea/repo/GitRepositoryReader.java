/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.repo;

import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.repo.RepoStateException;
import com.intellij.dvcs.repo.Repository;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.Function;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.impl.HashImpl;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBranchesCollection;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads information about the Git repository from Git service files located in the {@code .git} folder.
 * NB: works with {@link java.io.File}, i.e. reads from disk. Consider using caching.
 * Throws a {@link RepoStateException} in the case of incorrect Git file format.
 *
 * @author Kirill Likhodedov
 */
class GitRepositoryReader {

  private static final Logger LOG = Logger.getInstance(GitRepositoryReader.class);

  private static Pattern BRANCH_PATTERN          = Pattern.compile("ref: refs/heads/(\\S+)"); // branch reference in .git/HEAD
  // this format shouldn't appear, but we don't want to fail because of a space
  private static Pattern BRANCH_WEAK_PATTERN     = Pattern.compile(" *(ref:)? */?refs/heads/(\\S+)");
  private static Pattern COMMIT_PATTERN          = Pattern.compile("[0-9a-fA-F]+"); // commit hash

  @NonNls private static final String REFS_HEADS_PREFIX = "refs/heads/";
  @NonNls private static final String REFS_REMOTES_PREFIX = "refs/remotes/";

  @NotNull private final File          myGitDir;         // .git/
  @NotNull private final File          myHeadFile;       // .git/HEAD
  @NotNull private final File          myRefsHeadsDir;   // .git/refs/heads/
  @NotNull private final File          myRefsRemotesDir; // .git/refs/remotes/
  @NotNull private final File          myPackedRefsFile; // .git/packed-refs

  GitRepositoryReader(@NotNull File gitDir) {
    myGitDir = gitDir;
    DvcsUtil.assertFileExists(myGitDir, ".git directory not found in " + gitDir);
    myHeadFile = new File(myGitDir, "HEAD");
    DvcsUtil.assertFileExists(myHeadFile, ".git/HEAD file not found in " + gitDir);
    myRefsHeadsDir = new File(new File(myGitDir, "refs"), "heads");
    myRefsRemotesDir = new File(new File(myGitDir, "refs"), "remotes");
    myPackedRefsFile = new File(myGitDir, "packed-refs");
  }

  @Nullable
  private static Hash createHash(@Nullable String hash) {
    try {
      return hash == null ? null : HashImpl.build(hash);
    }
    catch (Throwable t) {
      LOG.info(t);
      return null;
    }
  }

  @NotNull
  public Repository.State readState() {
    if (isMergeInProgress()) {
      return Repository.State.MERGING;
    }
    if (isRebaseInProgress()) {
      return Repository.State.REBASING;
    }
    Head head = readHead();
    if (!head.isBranch) {
      return Repository.State.DETACHED;
    }
    return Repository.State.NORMAL;
  }

  /**
   * Finds current revision value.
   * @return The current revision hash, or <b>{@code null}</b> if current revision is unknown - it is the initial repository state.
   */
  @Nullable
  String readCurrentRevision() {
    final Head head = readHead();
    if (!head.isBranch) { // .git/HEAD is a commit
      return head.ref;
    }

    // look in /refs/heads/<branch name>
    File branchFile = null;
    for (Map.Entry<String, File> entry : findLocalBranches().entrySet()) {
      if (GitBranchUtil.stripRefsPrefix(entry.getKey()).equals(head.ref)) {
        branchFile = entry.getValue();
      }
    }
    if (branchFile != null) {
      return readBranchFile(branchFile);
    }

    // finally look in packed-refs
    return findBranchRevisionInPackedRefs(head.ref);
  }

  /**
   * If the repository is on branch, returns the current branch
   * If the repository is being rebased, returns the branch being rebased.
   * In other cases of the detached HEAD returns {@code null}.
   */
  @Nullable
  GitLocalBranch readCurrentBranch() {
    Head head = readHead();
    if (head.isBranch) {
      String branchName = head.ref;
      String hash = readCurrentRevision();  // TODO we know the branch name, so no need to read head twice
      Hash h = createHash(hash);
      if (branchName == null || h == null) {
        return null;
      }
      return new GitLocalBranch(branchName, h);
    }
    if (isRebaseInProgress()) {
      GitLocalBranch branch = readRebaseBranch("rebase-apply");
      if (branch == null) {
        branch = readRebaseBranch("rebase-merge");
      }
      return branch;
    }
    return null;
  }

  /**
   * Reads {@code .git/rebase-apply/head-name} or {@code .git/rebase-merge/head-name} to find out the branch which is currently being rebased,
   * and returns the {@link GitBranch} for the branch name written there, or null if these files don't exist.
   */
  @Nullable
  private GitLocalBranch readRebaseBranch(@NonNls String rebaseDirName) {
    File rebaseDir = new File(myGitDir, rebaseDirName);
    if (!rebaseDir.exists()) {
      return null;
    }
    File headName = new File(rebaseDir, "head-name");
    if (!headName.exists()) {
      return null;
    }

    String branchName;
    try {
      branchName = DvcsUtil.tryLoadFile(headName);
    }
    catch (RepoStateException e) {
      LOG.error(e);
      return null;
    }

    File branchFile = findBranchFile(branchName);
    if (!branchFile.exists()) { // can happen when rebasing from detached HEAD: IDEA-93806
      return null;
    }
    Hash hash = createHash(readBranchFile(branchFile));
    if (hash == null) {
      return null;
    }
    if (branchName.startsWith(REFS_HEADS_PREFIX)) {
      branchName = branchName.substring(REFS_HEADS_PREFIX.length());
    }
    return new GitLocalBranch(branchName, hash);
  }

  @NotNull
  private File findBranchFile(@NotNull String branchName) {
    return new File(myGitDir.getPath() + File.separator + branchName);
  }

  private boolean isMergeInProgress() {
    File mergeHead = new File(myGitDir, "MERGE_HEAD");
    return mergeHead.exists();
  }

  private boolean isRebaseInProgress() {
    File f = new File(myGitDir, "rebase-apply");
    if (f.exists()) {
      return true;
    }
    f = new File(myGitDir, "rebase-merge");
    return f.exists();
  }

  /**
   * Reads the {@code .git/packed-refs} file and tries to find the revision hash for the given reference (branch actually).
   * @param ref short name of the reference to find. For example, {@code master}.
   * @return commit hash, or {@code null} if the given ref wasn't found in {@code packed-refs}
   */
  @Nullable
  private String findBranchRevisionInPackedRefs(final String ref) {
    if (!myPackedRefsFile.exists()) {
      return null;
    }

    try {
      List<HashAndName> hashAndNames = readPackedRefsFile(new Condition<HashAndName>() {
        @Override
        public boolean value(HashAndName hashAndName) {
          return hashAndName.name.endsWith(ref);
        }
      });
      HashAndName item = ContainerUtil.getFirstItem(hashAndNames);
      return item == null ? null : item.hash;
    }
    catch (RepoStateException e) {
      LOG.error(e);
      return null;
    }
  }

  /**
   * @param firstMatchCondition If specified, we read the packed-refs file until the first entry which matches the given condition,
   *                            and return a singleton list of this entry.
   *                            If null, the whole file is read, and all valid entries are returned.
   */
  private List<HashAndName> readPackedRefsFile(@Nullable final Condition<HashAndName> firstMatchCondition) throws RepoStateException {
    return DvcsUtil.tryOrThrow(new Callable<List<HashAndName>>() {
      @Override
      public List<HashAndName> call() throws Exception {
        List<HashAndName> hashAndNames = ContainerUtil.newArrayList();
        BufferedReader reader = null;
        try {
          reader = new BufferedReader(new FileReader(myPackedRefsFile));
          for (String line = reader.readLine(); line != null ; line = reader.readLine()) {
            HashAndName hashAndName = parsePackedRefsLine(line);
            if (hashAndName == null) {
              continue;
            }
            if (firstMatchCondition != null) {
              if (firstMatchCondition.value(hashAndName)) {
                return Collections.singletonList(hashAndName);
              }
            }
            else {
              hashAndNames.add(hashAndName);
            }
          }
        }
        finally {
          if (reader != null) {
            reader.close();
          }
        }
        return hashAndNames;
      }
    }, myPackedRefsFile);
  }

  /**
   * @return the list of local branches in this Git repository.
   *         key is the branch name, value is the file.
   */
  private Map<String, File> findLocalBranches() {
    final Map<String, File> branches = new HashMap<String, File>();
    if (!myRefsHeadsDir.exists()) {
      return branches;
    }
    FileUtil.processFilesRecursively(myRefsHeadsDir, new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (!file.isDirectory()) {
          String relativePath = FileUtil.getRelativePath(myGitDir, file);
          if (relativePath != null) {
            branches.put(FileUtil.toSystemIndependentName(relativePath), file);
          }
        }
        return true;
      }
    });
    return branches;
  }

  @NotNull 
  GitBranchesCollection readBranches(@NotNull Collection<GitRemote> remotes) {
    Map<String, String> data = readBranchRefsFromFiles();
    return createBranchesFromData(remotes, data);
  }

  private Map<String, String> readBranchRefsFromFiles() {
    Map<String, String> result = readFromPackedRefs(); // reading from packed-refs first to overwrite values by values from unpacked refs
    for (Map.Entry<String, File> entry : findLocalBranches().entrySet()) {
      String branchName = entry.getKey();
      File file = entry.getValue();
      String value = loadHashFromBranchFile(file);
      if (value != null) {
        result.put(branchName, value);
      }
    }
    result.putAll(readUnpackedRemoteBranches());
    return result;
  }

  @NotNull
  private static GitBranchesCollection createBranchesFromData(@NotNull Collection<GitRemote> remotes, @NotNull Map<String, String> data) {
    Set<GitLocalBranch> localBranches = new HashSet<GitLocalBranch>();
    Set<GitRemoteBranch> remoteBranches = new HashSet<GitRemoteBranch>();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      String refName = entry.getKey();
      String value = entry.getValue();

      Hash hash = createHash(value);
      if (hash != null) {
        if (refName.startsWith(REFS_HEADS_PREFIX)) {
          localBranches.add(new GitLocalBranch(refName, hash));
        }
        else if (refName.startsWith(REFS_REMOTES_PREFIX)) {
          GitRemoteBranch remoteBranch = parseRemoteBranch(refName, hash, remotes);
          if (remoteBranch != null) {
            remoteBranches.add(remoteBranch);
          }
        }
      }
      else {
        LOG.warn("Couldn't parse hash from [" + value + "]");
      }
    }
    return new GitBranchesCollection(localBranches, remoteBranches);
  }

  @Nullable
  private static String loadHashFromBranchFile(@NotNull File branchFile) {
    return DvcsUtil.tryLoadFileOrReturn(branchFile, null);
  }

  @NotNull
  private Map<String, String> readUnpackedRemoteBranches() {
    if (!myRefsRemotesDir.exists()) {
      return Collections.emptyMap();
    }
    final Map<String, String> result = new HashMap<String, String>();
    FileUtil.processFilesRecursively(myRefsRemotesDir, new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (!file.isDirectory() && !file.getName().equalsIgnoreCase(GitRepositoryFiles.HEAD)) {
          String relativePath = FileUtil.getRelativePath(myGitDir, file);
          if (relativePath != null) {
            String branchName = FileUtil.toSystemIndependentName(relativePath);
            String hash = loadHashFromBranchFile(file);
            if (hash != null) {
              result.put(branchName, hash);
            }
          }
        }
        return true;
      }
    });
    return result;
  }

  @NotNull
  private Map<String, String> readFromPackedRefs() {
    if (!myPackedRefsFile.exists()) {
      return Collections.emptyMap();
    }
    try {
      List<HashAndName> hashAndNames = readPackedRefsFile(null);
      return ContainerUtil.map2Map(hashAndNames, new Function<HashAndName, Pair<String, String>>() {
        @Override
        public Pair<String, String> fun(HashAndName value) {
          return Pair.create(value.name, value.hash);
        }
      });
    }
    catch (RepoStateException e) {
      LOG.error(e);
      return Collections.emptyMap();
    }
  }

  @Nullable
  private static GitRemoteBranch parseRemoteBranch(@NotNull String fullBranchName,
                                                   @NotNull Hash hash,
                                                   @NotNull Collection<GitRemote> remotes) {
    String stdName = GitBranchUtil.stripRefsPrefix(fullBranchName);

    int slash = stdName.indexOf('/');
    if (slash == -1) { // .git/refs/remotes/my_branch => git-svn
      return new GitSvnRemoteBranch(fullBranchName, hash);
    }
    else {
      String remoteName = stdName.substring(0, slash);
      String branchName = stdName.substring(slash + 1);
      GitRemote remote = GitUtil.findRemoteByName(remotes, remoteName);
      if (remote == null) {
        // user may remove the remote section from .git/config, but leave remote refs untouched in .git/refs/remotes
        LOG.debug(String.format("No remote found with the name [%s]. All remotes: %s", remoteName, remotes));
        GitRemote fakeRemote = new GitRemote(remoteName, ContainerUtil.<String>emptyList(), Collections.<String>emptyList(),
                                             Collections.<String>emptyList(), Collections.<String>emptyList());
        return new GitStandardRemoteBranch(fakeRemote, branchName, hash);
      }
      return new GitStandardRemoteBranch(remote, branchName, hash);
    }
  }

  @Nullable
  private static String readBranchFile(@NotNull File branchFile) {
    return DvcsUtil.tryLoadFileOrReturn(branchFile, null);
  }

  @NotNull
  private Head readHead() {
    String headContent;
    try {
      headContent = DvcsUtil.tryLoadFile(myHeadFile);
    }
    catch (RepoStateException e) {
      LOG.error(e);
      return new Head(false, null);
    }
    Matcher matcher = BRANCH_PATTERN.matcher(headContent);
    if (matcher.matches()) {
      return new Head(true, matcher.group(1));
    }

    if (COMMIT_PATTERN.matcher(headContent).matches()) {
      return new Head(false, headContent);
    }
    matcher = BRANCH_WEAK_PATTERN.matcher(headContent);
    if (matcher.matches()) {
      LOG.info(".git/HEAD has not standard format: [" + headContent + "]. We've parsed branch [" + matcher.group(1) + "]");
      return new Head(true, matcher.group(1));
    }
    LOG.error(new RepoStateException("Invalid format of the .git/HEAD file: [" + headContent + "]"));
    return new Head(false, null);
  }

  /**
   * Parses a line from the .git/packed-refs file returning a pair of hash and ref name.
   * Comments and tags are ignored, and null is returned.
   * Incorrectly formatted lines are ignored, a warning is printed to the log, null is returned.
   * A line indicating a hash which an annotated tag (specified in the previous line) points to, is ignored: null is returned.
   */
  @Nullable
  private static HashAndName parsePackedRefsLine(@NotNull String line) {
    line = line.trim();
    if (line.isEmpty()) {
      return null;
    }
    char firstChar = line.charAt(0);
    if (firstChar == '#') { // ignoring comments
      return null;
    }
    if (firstChar == '^') {
      // ignoring the hash which an annotated tag above points to
      return null;
    }
    String hash = null;
    int i;
    for (i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (!Character.isLetterOrDigit(c)) {
        hash = line.substring(0, i);
        break;
      }
    }
    if (hash == null) {
      LOG.warn("Ignoring invalid packed-refs line: [" + line + "]");
      return null;
    }

    String branch = null;
    int start = i;
    if (start < line.length() && line.charAt(start++) == ' ') {
      for (i = start; i < line.length(); i++) {
        char c = line.charAt(i);
        if (Character.isWhitespace(c)) {
          break;
        }
      }
      branch = line.substring(start, i);
    }

    if (branch == null) {
      LOG.warn("Ignoring invalid packed-refs line: [" + line + "]");
      return null;
    }
    return new HashAndName(shortBuffer(hash.trim()), shortBuffer(branch));
  }

  @NotNull
  private static String shortBuffer(String raw) {
    return new String(raw);
  }

  private static class HashAndName {
    private final String hash;
    private final String name;

    public HashAndName(String hash, String name) {
      this.hash = hash;
      this.name = name;
    }
  }

  /**
   * Container to hold two information items: current .git/HEAD value and is Git on branch.
   */
  private static class Head {
    @Nullable private final String ref;
    private final boolean isBranch;

    Head(boolean branch, @Nullable String ref) {
      isBranch = branch;
      this.ref = ref;
    }
  }
}
