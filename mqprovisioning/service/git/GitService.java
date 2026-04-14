package com.company.mqprovisioning.service.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                log.info("Repository {} already exists, pulling latest changes", repoName);
                // Use the git CLI so partial-clone / sparse-checkout is handled correctly.
                // JGit does not support the promisor protocol and would fail with
                // MissingObjectException when resetting to a partial-clone ref.
                syncRepo(repoPath, hieradataRepoUrl, "master");
                Git git = Git.open(repoPath.toFile());
                repoCache.put(repoName, git);
            } else {
                log.info("Sparse-cloning repository {} from {}", repoName, hieradataRepoUrl);
                Files.createDirectories(repoPath.getParent());
                sparseClone(hieradataRepoUrl, repoPath, "master", HIERADATA_SPARSE_PATHS);
                Git git = Git.open(repoPath.toFile());
                repoCache.put(repoName, git);
            }
        } catch (IOException | GitAPIException e) {
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
                    syncRepo(repoPath, puppetRepoUrl, "prod");
                    Git git = Git.open(repoPath.toFile());
                    repoCache.put(repoName, git);
            } else {
                log.info("Sparse-cloning repository {} from {}", repoName, puppetRepoUrl);
                Files.createDirectories(repoPath.getParent());
                sparseClone(puppetRepoUrl, repoPath, "prod", PUPPET_SPARSE_PATHS);
                Git git = Git.open(repoPath.toFile());
                repoCache.put(repoName, git);
            }
        } catch (IOException | GitAPIException e) {
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

            // Reset repository if it's in a bad state (MERGING, REBASING, etc.)
            resetIfBadState(git, repoName);

            // Checkout main först
            git.checkout()
                    .setName("master")
                    .call();

            // Pull senaste ändringarna
            git.fetch()
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/master")
                    .call();

            Path acmqPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), "role/acmq.yaml");
            log.info("Checking file at: {}", acmqPath);
            log.info("File exists: {}", Files.exists(acmqPath));
            if (Files.exists(acmqPath)) {
                log.info("File size: {} bytes", Files.size(acmqPath));
            }

            // Skapa och checkout ny branch
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

            // Reset repository if it's in a bad state (MERGING, REBASING, etc.)
            resetIfBadState(git, repoName);

            // Checkout main först
            git.checkout()
                    .setName("prod")
                    .call();

            // Pull senaste ändringarna
            git.fetch()
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/prod")
                    .call();

            // Skapa och checkout ny branch
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call();

            log.info("Created branch {} in repository {}", branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error creating branch {} in repository {}", branchName, repoName, e);
            throw new RuntimeException("Kunde inte skapa branch: " + branchName, e);
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
        // Enkel implementation - använd ny content direkt (för YAML som hanteras av services)
        if (existing.isEmpty()) {
            return newContent;
        }
        // För YAML-filer hanteras merge i services (AcmqYamlService etc), så använd det nya
        return newContent;
    }

    private String mergeXmlErbContent(String existing, String newContent) {
        // Intelligent merge för XML/ERB filer
        if (existing.isEmpty()) {
            return newContent;
        }

        // Leta efter </security-settings> eller liknande closing tags
        // Infoga nytt innehåll INNAN closing tag
        if (existing.contains("</security-settings>")) {
            int insertPos = existing.indexOf("</security-settings>");
            StringBuilder result = new StringBuilder(existing);
            result.insert(insertPos, newContent + "\n");
            return result.toString();
        }

        // Om vi inte hittar rätt plats, lägg till i slutet
        log.warn("Could not find proper insertion point in XML/ERB, appending");
        return existing + "\n" + newContent;
    }

    /**
     * Syncs an existing local repo to the latest state of {@code branch} on the remote.
     * Uses the git CLI instead of JGit because JGit does not understand the partial-clone
     * promisor protocol – it would throw MissingObjectException when resetting to a ref
     * whose blobs were filtered out by {@code --filter=blob:none}.
     */
    private void syncRepo(Path repoPath, String repoUrl, String branch) throws IOException {
        try {
            String authenticatedUrl = buildAuthenticatedUrl(repoUrl);

            // Keep the stored remote URL up to date.
            runGitCommand(repoPath, Arrays.asList("git", "remote", "set-url", "origin", authenticatedUrl));

            // Abort any in-progress merge/rebase/cherry-pick before touching anything.
            runGitCommand(repoPath, Arrays.asList("git", "reset", "--hard", "HEAD"));

            // Fetch only the target branch; --filter is read from .git/config (partial clone).
            runGitCommand(repoPath, Arrays.asList("git", "fetch", "--depth=1", "origin", branch));

            runGitCommand(repoPath, Arrays.asList("git", "checkout", branch));
            runGitCommand(repoPath, Arrays.asList("git", "reset", "--hard", "origin/" + branch));

            log.info("Synced {} to origin/{}", repoPath.getFileName(), branch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sync interrupted for " + repoPath, e);
        }
    }

    /**
     * Clones a repository using sparse checkout so that only {@code sparsePaths} directories
     * are written to disk, and using {@code --filter=blob:none} so that blobs that fall
     * outside the sparse cone are never downloaded from the server.
     *
     * <p>Requires Git 2.25+ on the host and partial-clone support on the remote server
     * (Bitbucket Cloud and Bitbucket Data Center 7.x+ both support this).
     */
    private void sparseClone(String repoUrl, Path repoPath, String branch, List<String> sparsePaths)
            throws IOException {
        try {
            log.info("Sparse-cloning {} (branch: {}) – paths: {}", repoUrl, branch, sparsePaths);

            // Embed credentials in the URL so the git CLI does not prompt interactively.
            String authenticatedUrl = buildAuthenticatedUrl(repoUrl);

            // Download commit + tree objects only; no blobs yet.
            runGitCommand(repoPath.getParent(), Arrays.asList(
                    "git", "clone",
                    "--filter=blob:none",
                    "--no-checkout",
                    "--depth=1",
                    "--branch", branch,
                    authenticatedUrl,
                    repoPath.getFileName().toString()
            ));

            // Enable cone-mode sparse checkout (faster pattern matching).
            runGitCommand(repoPath, Arrays.asList("git", "sparse-checkout", "init", "--cone"));

            // Restrict working tree to the specified directories.
            List<String> setCmd = new ArrayList<>(Arrays.asList("git", "sparse-checkout", "set"));
            setCmd.addAll(sparsePaths);
            runGitCommand(repoPath, setCmd);

            // Checkout – only blobs for the sparse paths are fetched from the server.
            runGitCommand(repoPath, Arrays.asList("git", "checkout", branch));

            log.info("Sparse clone complete. Checked-out paths: {}", sparsePaths);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sparse clone interrupted for " + repoUrl, e);
        }
    }

    private void runGitCommand(Path directory, List<String> command) throws IOException, InterruptedException {
        log.debug("git> {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Git command failed (exit " + exitCode + "): "
                    + String.join(" ", command) + "\nOutput: " + output);
        }
        if (!output.isBlank()) {
            log.debug("git output: {}", output);
        }
    }

    /**
     * Reset repository if it's in a bad state (MERGING, REBASING, CHERRY_PICKING, etc.)
     * This can happen if a previous operation was interrupted.
     */
    private void resetIfBadState(Git git, String repoName) throws GitAPIException {
        RepositoryState state = git.getRepository().getRepositoryState();
        if (!state.canCommit()) {
            log.warn("Repository {} is in bad state: {}. Resetting to HEAD...", repoName, state);
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("HEAD")
                    .call();
            log.info("Repository {} reset successfully", repoName);
        }
    }

    /**
     * Inserts username and token into an HTTPS URL so the git CLI can authenticate
     * without an interactive terminal prompt, e.g.:
     * {@code https://user:token@bitbucket.example.com/scm/pup/repo.git}
     */
    private String buildAuthenticatedUrl(String repoUrl) {
        String encodedUser  = URLEncoder.encode(gitUsername, StandardCharsets.UTF_8);
        String encodedToken = URLEncoder.encode(gitToken,    StandardCharsets.UTF_8);
        return repoUrl.replace("https://", "https://" + encodedUser + ":" + encodedToken + "@");
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        return new UsernamePasswordCredentialsProvider(gitUsername, gitToken);
    }
}