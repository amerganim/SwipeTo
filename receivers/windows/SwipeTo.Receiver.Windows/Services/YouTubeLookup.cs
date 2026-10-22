using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>One search result: video id, title, channel.</summary>
public sealed record YouTubeHit(string VideoId, string Title, string Channel);

/// <summary>
/// Finds the YouTube video a phone is watching from what YouTube's media session tells it (title and
/// channel; never the video id). Uses the official YouTube Data API with the user's own key: only the
/// title and channel are sent to Google, no media. Accepts exactly one exact match, else nothing:
/// opening the wrong video would be worse than asking the viewer to share it instead.
/// </summary>
public sealed partial class YouTubeLookup(HttpClient http, Func<string?> apiKey)
{
    private readonly Dictionary<string, string> _found = new(StringComparer.Ordinal);

    public bool HasKey => !string.IsNullOrWhiteSpace(apiKey());

    /// <summary>The video id for <paramref name="title"/> on <paramref name="channel"/>, or null when not exactly one.</summary>
    public async Task<string?> FindAsync(string title, string? channel, CancellationToken ct)
    {
        var key = apiKey();
        if (string.IsNullOrWhiteSpace(key)) return null;
        var cacheKey = $"{title}\n{channel}";
        lock (_found)
        {
            if (_found.TryGetValue(cacheKey, out var known)) return known;
        }

        var query = channel is null ? title : $"{title} {channel}";
        var url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10" +
                  $"&q={Uri.EscapeDataString(query)}&key={Uri.EscapeDataString(key)}";
        using var response = await http.GetAsync(url, ct);
        if (response.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.Forbidden)
        {
            throw new InvalidOperationException($"YouTube refused the search ({(int)response.StatusCode}): check the API key and that the YouTube Data API v3 is enabled for it");
        }
        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(ct));
        var hits = new List<YouTubeHit>();
        foreach (var item in json.RootElement.GetProperty("items").EnumerateArray())
        {
            if (!item.GetProperty("id").TryGetProperty("videoId", out var id)) continue;
            var snippet = item.GetProperty("snippet");
            hits.Add(new YouTubeHit(id.GetString()!, snippet.GetProperty("title").GetString() ?? "", snippet.GetProperty("channelTitle").GetString() ?? ""));
        }
        var match = Choose(hits, title, channel);
        if (match is not null)
        {
            lock (_found) _found[cacheKey] = match;
        }
        return match;
    }

    /// <summary>The one hit whose title equals <paramref name="title"/> (and channel <paramref name="channel"/>, when given).</summary>
    public static string? Choose(IEnumerable<YouTubeHit> hits, string title, string? channel)
    {
        var wanted = Normalize(title);
        var fits = hits.Where(h => Normalize(h.Title) == wanted)
            .Where(h => channel is null || string.Equals(Normalize(h.Channel), Normalize(channel), StringComparison.OrdinalIgnoreCase))
            .Select(h => h.VideoId)
            .Distinct()
            .ToList();
        return fits.Count == 1 ? fits[0] : null;
    }

    /// <summary>The API returns HTML-escaped titles ("&amp;#39;"); compare text, ignoring runs of whitespace.</summary>
    private static string Normalize(string s) => Spaces().Replace(WebUtility.HtmlDecode(s), " ").Trim();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Spaces();
}
