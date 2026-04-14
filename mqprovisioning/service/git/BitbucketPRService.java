package com.company.mqprovisioning.service.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bitbucket Server REST API integration för att skapa Pull Requests.
 *
 * API-dokumentation: https://developer.atlassian.com/server/bitbucket/rest/
 * Endpoint: POST /rest/api/1.0/projects/{projectKey}/repos/{repositorySlug}/pull-requests
 */
@Slf4j
@Service
public class BitbucketPRService {

    @Value("${git.bitbucket.api.url}")
    private String bitbucketApiUrl;

    @Value("${git.bitbucket.token}")
    private String bitbucketToken;

    @Value("${git.bitbucket.project.key}")
    private String projectKey;

    @Value("${git.bitbucket.hieradata.repo}")
    private String hieradataRepo;

    @Value("${git.bitbucket.puppet.repo}")
    private String puppetRepo;

    @Value("${git.bitbucket.reviewer.group:ICC}")
    private String reviewerGroup;

    private final WebClient webClient;

    public BitbucketPRService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Skapa en Pull Request i Bitbucket Server med ICC-gruppen som default reviewers.
     *
     * @return URL till den skapade PR:en
     */
    public String createPullRequest(String repoName, String sourceBranch,
                                    String targetBranch, String title, String description) {
        String repoSlug = getRepoSlug(repoName);

        log.info("Creating pull request in Bitbucket Server for {}/{} from {} to {}",
                projectKey, repoSlug, sourceBranch, targetBranch);

        List<Map<String, Object>> reviewers = getGroupMembers();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("title", title);
        requestBody.put("description", description);
        requestBody.put("state", "OPEN");
        requestBody.put("open", true);
        requestBody.put("closed", false);
        requestBody.put("fromRef", Map.of(
                "id", "refs/heads/" + sourceBranch,
                "repository", Map.of(
                        "slug", repoSlug,
                        "project", Map.of("key", projectKey)
                )
        ));
        requestBody.put("toRef", Map.of(
                "id", "refs/heads/" + targetBranch,
                "repository", Map.of(
                        "slug", repoSlug,
                        "project", Map.of("key", projectKey)
                )
        ));
        requestBody.put("locked", false);
        if (!reviewers.isEmpty()) {
            requestBody.put("reviewers", reviewers);
            log.info("Adding {} reviewer(s) from group '{}'", reviewers.size(), reviewerGroup);
        }

        String endpoint = bitbucketApiUrl + "/projects/{projectKey}/repos/{repoSlug}/pull-requests";

        try {
            PullRequestResponse response = webClient.post()
                    .uri(endpoint, projectKey, repoSlug)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(PullRequestResponse.class)
                    .block();

            if (response != null && response.getLinks() != null &&
                    response.getLinks().getSelf() != null &&
                    !response.getLinks().getSelf().isEmpty()) {
                String prUrl = response.getLinks().getSelf().get(0).getHref();
                log.info("Successfully created pull request #{}: {}", response.getId(), prUrl);
                return prUrl;
            } else {
                throw new RuntimeException("Failed to create pull request - no response or URL");
            }
        } catch (WebClientResponseException e) {
            log.error("Bitbucket Server responded with HTTP {}: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to create pull request: HTTP " +
                    e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Error creating pull request in Bitbucket Server", e);
            throw new RuntimeException("Failed to create pull request: " + e.getMessage(), e);
        }
    }

    /**
     * Hämtar projektets reviewer-grupper från Bitbucket, plockar ut gruppen
     * med namn matchande reviewerGroup (default "ICC") och returnerar dess
     * medlemmar i det format som Bitbucket Server förväntar sig för reviewers.
     * Vid fel loggas en varning och en tom lista returneras så att PR-skapandet
     * inte blockeras.
     */
    private List<Map<String, Object>> getGroupMembers() {
        try {
            ReviewerGroupsResponse response = webClient.get()
                    .uri(bitbucketApiUrl + "/projects/{projectKey}/settings/reviewer-groups", projectKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .retrieve()
                    .bodyToMono(ReviewerGroupsResponse.class)
                    .block();

            if (response == null || response.getValues() == null) {
                log.warn("No reviewer groups found for project '{}'", projectKey);
                return List.of();
            }

            ReviewerGroup group = response.getValues().stream()
                    .filter(g -> reviewerGroup.equals(g.getName()))
                    .findFirst()
                    .orElse(null);

            if (group == null) {
                log.warn("Reviewer group '{}' not found in project '{}'", reviewerGroup, projectKey);
                return List.of();
            }

            if (group.getUsers() == null || group.getUsers().isEmpty()) {
                log.warn("Reviewer group '{}' has no members", reviewerGroup);
                return List.of();
            }

            List<Map<String, Object>> reviewers = group.getUsers().stream()
                    .filter(GroupMember::isActive)
                    .map(user -> Map.<String, Object>of("user", Map.of("name", user.getName())))
                    .collect(Collectors.toList());

            log.info("Found {} active member(s) in reviewer group '{}'", reviewers.size(), reviewerGroup);
            return reviewers;

        } catch (Exception e) {
            log.warn("Could not fetch reviewer group '{}', proceeding without reviewers: {}",
                    reviewerGroup, e.getMessage());
            return List.of();
        }
    }

    /**
     * Hämta PR-status
     */
    public PullRequestStatus getPullRequestStatus(String repoName, int prId) {
        String repoSlug = getRepoSlug(repoName);
        String endpoint = bitbucketApiUrl + "/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}";

        try {
            PullRequestResponse response = webClient.get()
                    .uri(endpoint, projectKey, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .retrieve()
                    .bodyToMono(PullRequestResponse.class)
                    .block();

            if (response != null) {
                String prUrl = response.getLinks().getSelf().get(0).getHref();
                return new PullRequestStatus(
                        response.getState(),
                        "MERGED".equals(response.getState()),
                        prUrl
                );
            }
            return null;
        } catch (Exception e) {
            log.error("Error getting pull request status", e);
            return null;
        }
    }

    private String getRepoSlug(String repoName) {
        return switch (repoName.toLowerCase()) {
            case "hieradata" -> hieradataRepo;
            case "puppet" -> puppetRepo;
            default -> throw new IllegalArgumentException("Unknown repository: " + repoName);
        };
    }

    // Response DTOs för Bitbucket Server

    @lombok.Data
    public static class ReviewerGroupsResponse {
        private List<ReviewerGroup> values;
        private boolean isLastPage;
    }

    @lombok.Data
    public static class ReviewerGroup {
        private Long id;
        private String name;
        private String description;
        private List<GroupMember> users;
    }

    @lombok.Data
    public static class GroupMember {
        private String name;
        private String displayName;
        private boolean active;
    }

    @lombok.Data
    public static class PullRequestResponse {
        private Integer id;
        private String title;
        private String description;
        private String state; // OPEN, MERGED, DECLINED
        private Links links;

        @lombok.Data
        public static class Links {
            private List<Link> self;

            @lombok.Data
            public static class Link {
                private String href;
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
}
