package com.company.mqprovisioning.service.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitService {

    @Autowired
    private BitbucketPRService bitbucketPRService;

    @Value("${git.hieradata.url}")
    private String hieradataRepoUrl;

    @Value("${git.puppet.url}")
    private String puppetRepoUrl;

    @Value("${git.username}")
    private String gitUsername;

    @Value("${git.bitbucket.token}")
    private String gitToken;

    private final Map<String, Git> repoCache = new HashMap<>();

    // Only the directories actually used by this application are checked out.
    // Hieradata: role/acmq.yaml and pm_env/pro/acmq.yaml
    private static final List<String> HIERADATA_SPARSE_PATHS = Arrays.asList("role", "pm_env/pro");
    // Puppet: modules/icc_artemis_broker/manifests/init.pp + templates/brokers/etc/broker.xml.erb
    private static final List<String> PUPPET_SPARSE_PATHS = Collections.singletonList("modules/icc_artemis_broker");


    public void prepareRepoHieradata() {
        String repoName = "hieradata";
        try {
            Path repoPath = Paths.get(
                    "app",
                    "gitrepo",
                    "pmsrc",
                    "pup",
                    "hieradata"
            );

            if (Files.exists(repoPath)) {
                log.info("Repository {} already exists, syncing with remote", repoName);
                try {
                    syncRepo(repoPath, hieradataRepoUrl, "master");
                } catch (IOException e) {
                    // The on-disk repo was likely created by the old git-CLI partial-clone
                    // approach. JGit cannot resolve blobs that were never downloaded
                    // (MissingObjectException). Delete it and do a fresh JGit clone.
                    log.warn("Sync failed for {} – deleting stale repo and re-cloning: {}",
                            repoName, e.getMessage());
                    closeAndEvict(repoName);
                    deleteDirectory(repoPath);
                    Files.createDirectories(repoPath.getParent());
                    sparseClone(hieradataRepoUrl, repoPath, "master", HIERADATA_SPARSE_PATHS);
                }
            } else {
                log.info("Sparse-cloning repository {} from {}", repoName, hieradataRepoUrl);
                Files.createDirectories(repoPath.getParent());
                sparseClone(hieradataRepoUrl, repoPath, "master", HIERADATA_SPARSE_PATHS);
            }
            Git git = Git.open(repoPath.toFile());
            repoCache.put(repoName, git);
        } catch (IOException e) {
            log.error("Error preparing repository {}", repoName, e);
            throw new RuntimeException("Kunde inte förbereda repository: " + repoName, e);
        }
    }

    public void prepareRepoPuppet() {
        String repoName = "puppet";
        try {
            Path repoPath = Paths.get(
                    "app",
                    "gitrepo",
                    "pmsrc",
                    "pup",
                    "puppet"
            );

            if (Files.exists(repoPath)) {
                log.info("Repository {} already exists, syncing with remote", repoName);
                try {
                    syncRepo(repoPath, puppetRepoUrl, "prod");
                } catch (IOException e) {
                    log.warn("Sync failed for {} – deleting stale repo and re-cloning: {}",
                            repoName, e.getMessage());
                    closeAndEvict(repoName);
                    deleteDirectory(repoPath);
                    Files.createDirectories(repoPath.getParent());
                    sparseClone(puppetRepoUrl, repoPath, "prod", PUPPET_SPARSE_PATHS);
                }
            } else {
                log.info("Sparse-cloning repository {} from {}", repoName, puppetRepoUrl);
                Files.createDirectories(repoPath.getParent());
                sparseClone(puppetRepoUrl, repoPath, "prod", PUPPET_SPARSE_PATHS);
            }
            Git git = Git.open(repoPath.toFile());
            repoCache.put(repoName, git);
        } catch (IOException e) {
            log.error("Error preparing repository {}", repoName, e);
            throw new RuntimeException("Kunde inte förbereda repository: " + repoName, e);
        }
    }

    public void createBranchHieradata(String branchName) {
        String repoName = "hieradata";
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path repoPath = git.getRepository().getWorkTree().toPath();
            syncRepo(repoPath, hieradataRepoUrl, "master");

            // Branch creation is a pure ref operation – no object fetching, safe for JGit.
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call();

            log.info("Created branch {} in repository {}", branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error creating branch {} in repository {}", branchName, repoName, e);
            throw new RuntimeException("Kunde inte skapa branch: " + branchName, e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createBranchPuppet(String branchName) {
        String repoName = "puppet";
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path repoPath = git.getRepository().getWorkTree().toPath();
            syncRepo(repoPath, puppetRepoUrl, "prod");

            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call();

            log.info("Created branch {} in repository {}", branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error creating branch {} in repository {}", branchName, repoName, e);
            throw new RuntimeException("Kunde inte skapa branch: " + branchName, e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void updateFile(String repoName, String filePath, String content) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path fullPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), filePath);

            // Läs befintlig fil om den finns
            String existingContent = "";
            if (Files.exists(fullPath)) {
                existingContent = Files.readString(fullPath);
            }

            // För ERB/XML filer, merga intelligent
            String mergedContent;
            if (filePath.endsWith(".erb") || filePath.endsWith(".xml")) {
                mergedContent = mergeXmlErbContent(existingContent, content);
            } else {
                // För andra filer (YAML etc), använd vanlig merge
                mergedContent = mergeContent(existingContent, content);
            }

            // Ensure parent directories exist
            if (fullPath.getParent() != null) {
                Files.createDirectories(fullPath.getParent());
            }

            Files.writeString(fullPath, mergedContent);

            git.add()
                    .addFilepattern(filePath)
                    .call();

            log.info("Updated file {} in repository {}", filePath, repoName);
        } catch (IOException | GitAPIException e) {
            log.error("Error updating file {} in repository {}", filePath, repoName, e);
            throw new RuntimeException("Kunde inte uppdatera fil: " + filePath, e);
        }
    }

    public void overwriteFile(String repoName, String filePath, String content) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path fullPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), filePath);

            // Ensure parent directories exist
            if (fullPath.getParent() != null) {
                Files.createDirectories(fullPath.getParent());
            }

            // Skriv över helt
            Files.writeString(fullPath, content);

            git.add()
                    .addFilepattern(filePath)
                    .call();

            log.info("Overwrote file {} in repository {}", filePath, repoName);
        } catch (IOException | GitAPIException e) {
            log.error("Error overwriting file {} in repository {}", filePath, repoName, e);
            throw new RuntimeException("Kunde inte skriva över fil: " + filePath, e);
        }
    }

    public String readFile(String repoName, String filePath) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path fullPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), filePath);

            if (Files.exists(fullPath)) {
                return Files.readString(fullPath);
            }

            return "";
        } catch (IOException e) {
            log.error("Error reading file {} in repository {}", filePath, repoName, e);
            throw new RuntimeException("Kunde inte läsa fil: " + filePath, e);
        }
    }

    public void commitAndPush(String repoName, String branchName, String commitMessage) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            git.commit()
                    .setMessage(commitMessage)
                    .call();

            git.push()
                    .setCredentialsProvider(getCredentialsProvider())
                    .setRemote("origin")
                    .add(branchName)
                    .call();

            log.info("Committed and pushed changes to branch {} in repository {}",
                    branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error committing and pushing to branch {} in repository {}",
                    branchName, repoName, e);
            throw new RuntimeException("Kunde inte commit och push: " + branchName, e);
        }
    }

    public void deleteLocalBranch(String repoName, String branchName) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }
            // Checkout master/prod innan vi tar bort branchen
            String mainBranch = repoName.equals("puppet") ? "prod" : "master";
            git.checkout().setName(mainBranch).call();
            git.branchDelete().setBranchNames(branchName).setForce(true).call();
            log.info("Deleted local branch {} in repository {}", branchName, repoName);
        } catch (GitAPIException e) {
            log.warn("Could not delete local branch {} in repository {}: {}", branchName, repoName, e.getMessage());
        }
    }

    public String createPullRequest(String repoName, String sourceBranch,
                                    String targetBranch, String title, String description) {
        // Använd Bitbucket API för att skapa pull request
        return bitbucketPRService.createPullRequest(repoName, sourceBranch, targetBranch, title, description);
    }

    private String mergeContent(String existing, String newContent) {
        if (existing.isEmpty()) {
            return newContent;
        }
        return newContent;
    }

    private String mergeXmlErbContent(String existing, String newContent) {
        if (existing.isEmpty()) {
            return newContent;
        }

        if (existing.contains("</security-settings>")) {
            int insertPos = existing.indexOf("</security-settings>");
            StringBuilder result = new StringBuilder(existing);
            result.insert(insertPos, newContent + "\n");
            return result.toString();
        }

        log.warn("Could not find proper insertion point in XML/ERB, appending");
        return existing + "\n" + newContent;
    }

    /**
     * Syncs an existing local repo to the latest state of {@code branch} on the remote
     * using the JGit API (no system git binary required).
     *
     * <p>Uses {@code setDepth(1)} on fetch to keep the clone shallow – only the latest
     * commit is transferred, no history.
     */
    private void syncRepo(Path repoPath, String repoUrl, String branch) throws IOException {
        try (Git git = Git.open(repoPath.toFile())) {
            // Keep the stored remote URL up to date.
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", repoUrl);
            config.save();

            // Abort any in-progress operation before touching anything.
            git.reset().setMode(ResetCommand.ResetType.HARD).call();

            // Fetch with depth=1 so only the latest commit is transferred, no history.
            git.fetch()
               .setRemote("origin")
               .setCredentialsProvider(getCredentialsProvider())
               .setDepth(1)
               .call();

            // Switch to the branch if not already on it.
            String currentBranch = git.getRepository().getBranch();
            if (!branch.equals(currentBranch)) {
                git.checkout().setName(branch).call();
            }

            // Hard-reset to the fetched remote tracking ref.
            git.reset()
               .setMode(ResetCommand.ResetType.HARD)
               .setRef("refs/remotes/origin/" + branch)
               .call();

            log.info("Synced {} to origin/{}", repoPath.getFileName(), branch);
        } catch (GitAPIException e) {
            throw new IOException("Sync failed for " + repoPath, e);
        }
    }

    /**
     * Clones a repository using JGit with two optimisations:
     *
     * <ol>
     *   <li>{@code setDepth(1)} – shallow clone: only the latest commit and its objects
     *       are transferred; the full history is skipped. This is the largest saving
     *       available without a system git binary.</li>
     *   <li>Sparse checkout – after the clone, {@code core.sparseCheckout=true} and a
     *       patterns file are written so that only the files under {@code sparsePaths}
     *       are materialised in the working tree. All blob objects for HEAD are still
     *       stored in {@code .git/objects} (JGit cannot filter them server-side without
     *       the promisor protocol), but the working tree stays small.</li>
     * </ol>
     */
    private void sparseClone(String repoUrl, Path repoPath, String branch, List<String> sparsePaths)
            throws IOException {
        try {
            log.info("Sparse-cloning {} (branch: {}, depth: 1) – paths: {}", repoUrl, branch, sparsePaths);

            // Clone with no-checkout and depth=1: only the latest commit is fetched,
            // no history. Files are not yet written to disk.
            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoPath.toFile())
                    .setCredentialsProvider(getCredentialsProvider())
                    .setBranch(branch)
                    .setNoCheckout(true)
                    .setDepth(1)
                    .call()) {

                // Enable sparse checkout so that checkout only writes the needed paths.
                StoredConfig config = git.getRepository().getConfig();
                config.setBoolean("core", null, "sparseCheckout", true);
                config.save();

                // Write the sparse-checkout patterns file (non-cone mode).
                // Trailing slash matches the full directory subtree.
                Path sparseFile = repoPath.resolve(".git/info/sparse-checkout");
                Files.createDirectories(sparseFile.getParent());
                String patterns = sparsePaths.stream()
                        .map(p -> p + "/")
                        .collect(Collectors.joining("\n"));
                Files.writeString(sparseFile, patterns);

                // Checkout – JGit respects core.sparseCheckout and the patterns file,
                // so only files under the configured paths are written to disk.
                git.checkout().setName(branch).call();
            }

            log.info("Sparse clone complete. Checked-out paths: {}", sparsePaths);
        } catch (GitAPIException e) {
            throw new IOException("Sparse clone failed for " + repoUrl, e);
        }
    }

    /** Closes and removes a cached {@link Git} instance so its directory can be deleted. */
    private void closeAndEvict(String repoName) {
        Git stale = repoCache.remove(repoName);
        if (stale != null) {
            stale.close();
        }
    }

    /**
     * Recursively deletes a directory tree. Used to remove stale partial-clone repos
     * that JGit cannot sync (MissingObjectException).
     */
    private void deleteDirectory(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try {
                          Files.delete(p);
                      } catch (IOException e) {
                          log.warn("Could not delete {}: {}", p, e.getMessage());
                      }
                  });
        }
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        return new UsernamePasswordCredentialsProvider(gitUsername, gitToken);
    }
}
