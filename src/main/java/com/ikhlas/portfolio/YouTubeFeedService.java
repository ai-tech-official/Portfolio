package com.ikhlas.portfolio;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@Service
public class YouTubeFeedService {
    private static final Duration CACHE_DURATION = Duration.ofMinutes(15);
    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("^UC[A-Za-z0-9_-]{20,}$");
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String YOUTUBE_NAMESPACE = "http://www.youtube.com/xml/schemas/2015";
    private static final String MEDIA_NAMESPACE = "http://search.yahoo.com/mrss/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String channelId;
    private volatile FeedSnapshot cachedSnapshot;

    public YouTubeFeedService(@Value("${youtube.channel-id:}") String channelId) {
        this.channelId = channelId == null ? "" : channelId.strip();
    }

    public FeedSnapshot latestVideos() {
        if (channelId.isBlank()) {
            throw new FeedUnavailableException(
                    "YouTube is not configured yet. Set YOUTUBE_CHANNEL_ID to your channel ID and restart the app.");
        }
        if (!CHANNEL_ID_PATTERN.matcher(channelId).matches()) {
            throw new FeedUnavailableException("YOUTUBE_CHANNEL_ID must be a YouTube channel ID beginning with UC.");
        }

        FeedSnapshot snapshot = cachedSnapshot;
        if (isFresh(snapshot)) {
            return snapshot;
        }

        synchronized (this) {
            snapshot = cachedSnapshot;
            if (isFresh(snapshot)) {
                return snapshot;
            }
            cachedSnapshot = fetchFeed();
            return cachedSnapshot;
        }
    }

    private boolean isFresh(FeedSnapshot snapshot) {
        return snapshot != null && snapshot.fetchedAt().plus(CACHE_DURATION).isAfter(Instant.now());
    }

    private FeedSnapshot fetchFeed() {
        URI feedUri = URI.create("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
        HttpRequest request = HttpRequest.newBuilder(feedUri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "IkhlasPortfolio/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new FeedUnavailableException("YouTube's video feed is temporarily unavailable. Try again later.");
            }
            List<YouTubeVideo> videos = parseVideos(response.body());
            return new FeedSnapshot(videos, Instant.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FeedUnavailableException("The YouTube feed request was interrupted.");
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw new FeedUnavailableException("Could not load the YouTube feed. Check the server connection and retry.");
        }
    }

    private List<YouTubeVideo> parseVideos(String xml)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");
        List<YouTubeVideo> videos = new ArrayList<>();

        for (int index = 0; index < entries.getLength() && videos.size() < 6; index++) {
            Element entry = (Element) entries.item(index);
            String videoId = textOf(entry, YOUTUBE_NAMESPACE, "videoId");
            if (videoId.isBlank()) {
                continue;
            }

            String thumbnailUrl = thumbnailOf(entry);
            if (thumbnailUrl.isBlank()) {
                thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            }
            videos.add(new YouTubeVideo(
                    textOf(entry, ATOM_NAMESPACE, "title"),
                    videoId,
                    parseInstant(textOf(entry, ATOM_NAMESPACE, "published")),
                    thumbnailUrl,
                    "https://www.youtube.com/watch?v=" + videoId,
                    textOf(entry, MEDIA_NAMESPACE, "description"),
                    null));
        }
        return List.copyOf(videos);
    }

    private String thumbnailOf(Element entry) {
        NodeList thumbnails = entry.getElementsByTagNameNS(MEDIA_NAMESPACE, "thumbnail");
        if (thumbnails.getLength() == 0) {
            return "";
        }
        Node thumbnail = thumbnails.item(0);
        return thumbnail instanceof Element element ? element.getAttribute("url") : "";
    }

    private String textOf(Element parent, String namespace, String localName) {
        NodeList elements = parent.getElementsByTagNameNS(namespace, localName);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().strip();
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    public record FeedSnapshot(List<YouTubeVideo> videos, Instant fetchedAt) {
    }

    public static class FeedUnavailableException extends RuntimeException {
        public FeedUnavailableException(String message) {
            super(message);
        }
    }
}
