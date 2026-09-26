package com.ikhlas.portfolio;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class YouTubeVideoController {
    private final YouTubeFeedService feedService;
    private final GeminiVideoSummaryService summaryService;
    private final ProjectVideoFileService projectVideoFileService;

    public YouTubeVideoController(
            YouTubeFeedService feedService,
            GeminiVideoSummaryService summaryService,
            ProjectVideoFileService projectVideoFileService) {
        this.feedService = feedService;
        this.summaryService = summaryService;
        this.projectVideoFileService = projectVideoFileService;
    }

    @GetMapping("/api/videos")
    public FeedResponse latestVideos() {
        YouTubeFeedService.FeedSnapshot snapshot = feedService.latestVideos();
        List<YouTubeVideo> videos = projectVideoFileService.withProjectLinks(snapshot.videos());
        return new FeedResponse(
            summaryService.addSummaries(videos),
                snapshot.fetchedAt(),
                summaryService.isEnabled());
    }

    @ExceptionHandler(YouTubeFeedService.FeedUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> feedUnavailable(YouTubeFeedService.FeedUnavailableException exception) {
        return Map.of("error", exception.getMessage());
    }

    public record FeedResponse(List<YouTubeVideo> videos, Instant fetchedAt, boolean aiSummariesEnabled) {
    }
}
