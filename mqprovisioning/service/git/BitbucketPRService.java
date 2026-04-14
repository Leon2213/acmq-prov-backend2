package com.company.mqprovisioning.service.git;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bitbucket API Integration för att skapa Pull Requests
 *
 * Dokumentation: https://developer.atlassian.com/cloud/bitbucket/rest/
 */
@Slf4j
@Service
public class BitbucketPRService {

    @Value("${git.bitbucket.api.url}")
    private String bitbucketApiUrl;

    @Value("${git.bitbucket.token}")
    private String bitbucketToken;

    private String workspace = "puppet";

    @Value("${git.bitbucket.reviewer.group:ICC}")
    private String reviewerGroup;

    @Value("${git.bitbucket.hieradata.repo}")
    private String hieradataRepo;

    @Value("${git.bitbucket.puppet.repo}")
    private String puppetRepo;

    private final WebClient webClient;

    public BitbucketPRService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Skapa en Pull Request i Bitbucket
     */
    public String createPullRequest(String repoName, String sourceBranch,
                                   String targetBranch, String title, String description) {
        System.out.println("reponame: " + repoName + " sourcebranch: " +  sourceBranch  + " targetbranch: " + targetBranch  + " title:" +  title  + " description: " + description);

        String repoSlug = getRepoSlug(repoName);

        log.info("Creating pull request in Bitbucket for {}/{} from {} to {}",
                workspace, repoSlug, sourceBranch, targetBranch);

        // Sök reviewers via workspace-användarsökning (samma som Bitbucket UI gör)
        List<Map<String, String>> reviewers = getReviewersByUserSearch(reviewerGroup)
                .stream()
                .map(accountId -> Map.of("account_id", accountId))
                .collect(Collectors.toList());

        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("title", title);
        requestBody.put("description", description);
        requestBody.put("source", Map.of("branch", Map.of("name", sourceBranch)));
        requestBody.put("destination", Map.of("branch", Map.of("name", targetBranch)));
        requestBody.put("close_source_branch", true);
        if (!reviewers.isEmpty()) {
            requestBody.put("reviewers", reviewers);
        }

        try {
            PullRequestResponse response = webClient.post()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests",
                         workspace, repoSlug)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(PullRequestResponse.class)
                    .block();

            if (response != null && response.getLinks() != null &&
                response.getLinks().getHtml() != null) {
                String prUrl = response.getLinks().getHtml().getHref();
                log.info("Successfully created pull request #{}: {}", response.getId(), prUrl);
                return prUrl;
            } else {
                throw new RuntimeException("Failed to create pull request - no response or URL");
            }
        } catch (Exception e) {
            log.error("Error creating pull request in Bitbucket", e);
            throw new RuntimeException("Failed to create pull request: " + e.getMessage(), e);
        }
    }


    /**
     * Hämta PR status
     */
    public PullRequestStatus getPullRequestStatus(String repoName, int prId) {
        String repoSlug = getRepoSlug(repoName);

        try {
            PullRequestResponse response = webClient.get()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}",
                         workspace, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .retrieve()
                    .bodyToMono(PullRequestResponse.class)
                    .block();

            if (response != null) {
                return new PullRequestStatus(
                    response.getState(),
                    "MERGED".equals(response.getState()),
                    response.getLinks().getHtml().getHref()
                );
            }
            return null;
        } catch (Exception e) {
            log.error("Error getting pull request status", e);
            return null;
        }
    }

    /**
     * Lägg till en kommentar på PR
     */
    public void addComment(String repoName, int prId, String comment) {
        String repoSlug = getRepoSlug(repoName);

        try {
            webClient.post()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/comments",
                         workspace, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .bodyValue(Map.of("content", Map.of("raw", comment)))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();

            log.info("Added comment to PR #{}", prId);
        } catch (Exception e) {
            log.warn("Failed to add comment: {}", e.getMessage());
        }
    }

    /**
     * Merge en PR (om godkänd)
     */
    public boolean mergePullRequest(String repoName, int prId, String commitMessage) {
        String repoSlug = getRepoSlug(repoName);

        try {
            Map<String, Object> mergeRequest = Map.of(
                "type", "pullrequest_merge",
                "message", commitMessage,
                "close_source_branch", true
            );

            webClient.post()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/merge",
                         workspace, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .bodyValue(mergeRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("Successfully merged PR #{}", prId);
            return true;
        } catch (Exception e) {
            log.error("Failed to merge PR #{}: {}", prId, e.getMessage());
            return false;
        }
    }

    /**
     * Lägg till reviewers till PR
     */
    public void addReviewers(String repoName, int prId, List<String> reviewers) {
        String repoSlug = getRepoSlug(repoName);

        try {
            // Bitbucket kräver att varje reviewer läggs till individuellt
            for (String reviewer : reviewers) {
                webClient.put()
                        .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/default-reviewers/{target_username}",
                             workspace, repoSlug, prId, reviewer)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                        .retrieve()
                        .bodyToMono(Void.class)
                        .block();
            }

            log.info("Added reviewers {} to PR #{}", String.join(",", reviewers), prId);
        } catch (Exception e) {
            log.warn("Failed to add reviewers: {}", e.getMessage());
        }
    }

    /**
     * Söker workspace-medlemmar vars display name eller nickname innehåller {@code searchQuery}.
     * Det är samma sökning som Bitbucket UI gör när du skriver i reviewer-fältet –
     * alltså en textbaserad användarsökning, inte en gruppmeddlemsuppslagning.
     *
     * Returnerar en lista med account_id (UUID) för matchande aktiva användare.
     */
    private List<String> getReviewersByUserSearch(String searchQuery) {
        log.info("Searching for reviewers matching '{}' in workspace '{}'", searchQuery, workspace);
        try {
            WorkspaceMembersResponse response = webClient.get()
                    .uri(bitbucketApiUrl + "/workspaces/{workspace}/members?pagelen=100", workspace)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .retrieve()
                    .bodyToMono(WorkspaceMembersResponse.class)
                    .block();

            List<String> accountIds = new ArrayList<>();
            if (response != null && response.getValues() != null) {
                String queryLower = searchQuery.toLowerCase();
                for (WorkspaceMembership membership : response.getValues()) {
                    if (membership.getUser() == null) continue;
                    CloudUser user = membership.getUser();
                    String displayName = Optional.ofNullable(user.getDisplayName()).orElse("").toLowerCase();
                    String nickname   = Optional.ofNullable(user.getNickname()).orElse("").toLowerCase();
                    if (displayName.contains(queryLower) || nickname.contains(queryLower)) {
                        accountIds.add(user.getAccountId());
                    }
                }
            }

            log.info("Found {} active member(s) in group '{}'", accountIds.size(), searchQuery);
            return accountIds;
        } catch (Exception e) {
            log.warn("Failed to search reviewers for '{}': {}", searchQuery, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String getRepoSlug(String repoName) {
        return switch (repoName.toLowerCase()) {
            case "hieradata" -> hieradataRepo;
            case "puppet" -> puppetRepo;
            default -> throw new IllegalArgumentException("Unknown repository: " + repoName);
        };
    }

    // Response DTOs
    @lombok.Data
    public static class PullRequestResponse {
        private Integer id;
        private String title;
        private String description;
        private String state; // OPEN, MERGED, DECLINED, SUPERSEDED
        private Links links;
        private Source source;
        private Destination destination;

        @lombok.Data
        public static class Links {
            private Link html;
            private Link self;

            @lombok.Data
            public static class Link {
                private String href;
            }
        }

        @lombok.Data
        public static class Source {
            private Branch branch;
            private Repository repository;

            @lombok.Data
            public static class Branch {
                private String name;
            }

            @lombok.Data
            public static class Repository {
                private String name;
            }
        }

        @lombok.Data
        public static class Destination {
            private Branch branch;
            private Repository repository;

            @lombok.Data
            public static class Branch {
                private String name;
            }

            @lombok.Data
            public static class Repository {
                private String name;
            }
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PullRequestStatus {
        private String state;
        private boolean merged;
        private String url;
    }

    // DTOs för workspace members-sökning (Bitbucket Cloud 2.0 API)

    @lombok.Data
    public static class WorkspaceMembersResponse {
        private List<WorkspaceMembership> values;
    }

    @lombok.Data
    public static class WorkspaceMembership {
        private CloudUser user;
    }

    @lombok.Data
    public static class CloudUser {
        @JsonProperty("account_id")
        private String accountId;

        @JsonProperty("display_name")
        private String displayName;

        private String nickname;
    }
}
