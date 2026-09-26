# MD. Ikhlas Portfolio

The portfolio is served by a small Spring Boot backend. Its YouTube section reads the channel's public uploads RSS feed, caches the response for 15 minutes, and displays up to six recent uploads. Gemini can optionally summarize new uploads from their public titles and descriptions; it does not watch or transcribe videos.

## Requirements

- Java 17 or newer
- Maven 3.9 or newer

## Run locally

The provided AI TECH channel is configured by default. Set a Gemini API key to enable summaries, then start Spring Boot from the project root:

```powershell
$env:GEMINI_API_KEY = "your-Gemini-API-key"
mvn spring-boot:run
```

To use a different YouTube channel, set `YOUTUBE_CHANNEL_ID` (the value beginning with `UC` in a `/channel/UC...` URL). The API key is read from the environment and is not stored in the repository.

Open `http://localhost:8080`. The page checks for new uploads every 10 minutes; the backend refreshes the YouTube feed at most every 15 minutes and caches summaries for the latest uploads in memory. Use the refresh button to request the latest cached feed immediately.

When opened directly as a `file://` page, the browser loads the channel uploads through rss2json because local files cannot call the Spring Boot API. The static fallback shows channel uploads but does not load `project-videos.txt` entries or Gemini summaries.

## Add project videos

Add one public YouTube video URL per line to `project-videos.txt`. The file is reread whenever the video API is requested, so save the file and refresh the page; restarting the app is not required. These links appear before the channel's newest uploads, and duplicate videos are shown once. YouTube's oEmbed endpoint supplies titles and thumbnails. Set `PROJECT_VIDEOS_FILE` if the file is stored elsewhere.

If `YOUTUBE_CHANNEL_ID` is unset, the app uses the provided AI TECH channel.
