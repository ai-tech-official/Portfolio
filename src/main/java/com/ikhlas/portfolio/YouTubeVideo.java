package com.ikhlas.portfolio;

import java.time.Instant;

public record YouTubeVideo(
        String title,
        String videoId,
        Instant publishedAt,
        String thumbnailUrl,
        String videoUrl,
        String description,
        String aiSummary) {
}
