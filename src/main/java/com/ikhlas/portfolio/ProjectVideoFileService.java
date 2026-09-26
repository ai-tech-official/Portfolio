package com.ikhlas.portfolio;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProjectVideoFileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectVideoFileService.class);
    private static final int MAX_PROJECT_VIDEOS = 12;
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Set<String> YOUTUBE_HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be");

    private final Path linksFile;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ConcurrentHashMap<String, YouTubeVideo> metadataCache = new ConcurrentHashMap<>();

    public ProjectVideoFileService(
            ObjectMapper objectMapper,
            @Value("${project.videos-file:project-videos.txt}") String linksFile) {
        this.objectMapper = objectMapper;
        this.linksFile = Path.of(linksFile);
    }

    public List<YouTubeVideo> withProjectLinks(List<YouTubeVideo> recentUploads) {
        LinkedHashSet<String> projectIds = new LinkedHashSet<>();
        for (String line : readLinks()) {
            String url = line.strip();
            if (url.isEmpty() || url.startsWith("#")) {
                continue;
            }
            String videoId = extractVideoId(url);
            if (videoId == null) {
                LOGGER.warn("Skipping an invalid YouTube link in {}.", linksFile);
                continue;
            }
            projectIds.add(videoId);
            if (projectIds.size() == MAX_PROJECT_VIDEOS) {
                break;
            }
        }

        metadataCache.keySet().retainAll(projectIds);
        LinkedHashMap<String, YouTubeVideo> mergedVideos = new LinkedHashMap<>();
        for (String videoId : projectIds) {
            mergedVideos.put(videoId, metadataCache.computeIfAbsent(videoId, this::loadMetadata));
        }
        for (YouTubeVideo recentUpload : recentUploads) {
            mergedVideos.putIfAbsent(recentUpload.videoId(), recentUpload);
        }
        return List.copyOf(mergedVideos.values());
    }

    private List<String> readLinks() {
        if (!Files.isRegularFile(linksFile)) {
            return List.of();
        }
        try {
            return Files.readAllLines(linksFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Unable to read project video links from {}: {}", linksFile, exception.getMessage());
            return List.of();
        }
    }

    private String extractVideoId(String link) {
        String normalizedLink = link.contains("://") ? link : "https://" + link;
        try {
            URI uri = URI.create(normalizedLink);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !scheme.equalsIgnoreCase("https") || host == null
                    || !YOUTUBE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return null;
            }

            String videoId = host.equalsIgnoreCase("youtu.be")
                    ? firstPathSegment(uri.getPath())
                    : videoIdFromYoutubePath(uri);
            return videoId != null && VIDEO_ID_PATTERN.matcher(videoId).matches() ? videoId : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String videoIdFromYoutubePath(URI uri) {
        if ("/watch".equals(uri.getPath())) {
            String query = uri.getRawQuery();
            if (query == null) {
                return null;
            }
            for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair.length == 2 && "v".equals(pair[0])) {
                    return pair[1];
                }
            }
            return null;
        }

        String[] segments = uri.getPath().split("/");
        if (segments.length >= 3 && Set.of("shorts", "embed", "live").contains(segments[1])) {
            return segments[2];
        }
        return null;
    }

    private String firstPathSegment(String path) {
        if (path == null || path.length() < 2) {
            return null;
        }
        String segment = path.substring(1).split("/")[0];
        return segment.isBlank() ? null : segment;
    }

    private YouTubeVideo loadMetadata(String videoId) {
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        String thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        String title = "YouTube project video";
        String encodedUrl = URLEncoder.encode(videoUrl, StandardCharsets.UTF_8);
        URI oEmbedUri = URI.create("https://www.youtube.com/oembed?url=" + encodedUrl + "&format=json");
        HttpRequest request = HttpRequest.newBuilder(oEmbedUri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode metadata = objectMapper.readTree(response.body());
                title = metadata.path("title").asText(title);
                thumbnailUrl = metadata.path("thumbnail_url").asText(thumbnailUrl);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("YouTube metadata lookup was interrupted for video {}.", videoId);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("YouTube metadata lookup failed for video {}: {}", videoId, exception.getMessage());
        }

        return new YouTubeVideo(title, videoId, null, thumbnailUrl, videoUrl, "", null);
    }
}