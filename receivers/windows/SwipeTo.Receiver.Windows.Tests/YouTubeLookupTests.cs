using System.Net;
using SwipeTo.Receiver.Windows.Services;

namespace SwipeTo.Receiver.Windows.Tests;

/// <summary>The YouTube lookup accepts exactly one exact match and never guesses (docs/protocol.md, 5.4).</summary>
public sealed class YouTubeLookupTests
{
    private static readonly YouTubeHit Official = new("aqz-KE-bpKQ", "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", "Blender");

    [Fact]
    public void Exact_title_on_the_right_channel_is_the_one()
    {
        var reupload = Official with { VideoId = "reupload01", Channel = "Some Reuploader" };
        var similar = Official with { VideoId = "similar001", Title = "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film (reaction)" };

        Assert.Equal("aqz-KE-bpKQ", YouTubeLookup.Choose([reupload, similar, Official], Official.Title, "Blender"));
    }

    [Fact]
    public void Html_escapes_and_spacing_from_the_api_do_not_matter()
    {
        var escaped = new YouTubeHit("abc123", "Rock &amp; Roll  isn&#39;t dead", "Chan");

        Assert.Equal("abc123", YouTubeLookup.Choose([escaped], "Rock & Roll isn't dead", "chan"));
    }

    [Fact]
    public void No_match_or_several_is_null_never_a_guess()
    {
        var twin = Official with { VideoId = "twin000001" };

        Assert.Null(YouTubeLookup.Choose([Official], "Big Buck Bunny", "Blender"));
        Assert.Null(YouTubeLookup.Choose([Official], Official.Title, "Another channel"));
        Assert.Null(YouTubeLookup.Choose([Official, twin], Official.Title, null));
        Assert.Null(YouTubeLookup.Choose([], Official.Title, "Blender"));
    }

    [Fact]
    public async Task Search_results_are_matched_and_cached()
    {
        var calls = 0;
        var api = new StubHandler(_ =>
        {
            calls++;
            return """{"items":[{"id":{"kind":"youtube#video","videoId":"aqz-KE-bpKQ"},"snippet":{"title":"Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film","channelTitle":"Blender"}},{"id":{"kind":"youtube#channel","channelId":"x"},"snippet":{"title":"Blender","channelTitle":"Blender"}}]}""";
        });
        var lookup = new YouTubeLookup(new HttpClient(api), () => "test-key");

        Assert.Equal("aqz-KE-bpKQ", await lookup.FindAsync(Official.Title, "Blender", CancellationToken.None));
        Assert.Equal("aqz-KE-bpKQ", await lookup.FindAsync(Official.Title, "Blender", CancellationToken.None));
        Assert.Equal(1, calls);
        Assert.Contains("key=test-key", api.LastUrl);
    }

    [Fact]
    public async Task Without_a_key_nothing_is_asked()
    {
        var api = new StubHandler(_ => throw new InvalidOperationException("must not be called"));

        Assert.Null(await new YouTubeLookup(new HttpClient(api), () => null).FindAsync("x", null, CancellationToken.None));
    }

    private sealed class StubHandler(Func<HttpRequestMessage, string> body) : HttpMessageHandler
    {
        public string LastUrl { get; private set; } = "";

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            LastUrl = request.RequestUri!.ToString();
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body(request)) });
        }
    }
}
