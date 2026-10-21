using System.Net;
using System.Net.Http.Headers;
using SwipeTo.Net.Sender;

namespace SwipeTo.Tools.Tests;

public sealed class MediaServerTests : IAsyncLifetime
{
    private readonly MediaServer _server = new();
    private readonly HttpClient _http = new();
    private readonly string _file = Path.GetTempFileName();
    private string _url = "";

    public async Task InitializeAsync()
    {
        await File.WriteAllBytesAsync(_file, Enumerable.Range(0, 10_000).Select(i => (byte)(i % 251)).ToArray());
        await _server.StartAsync();
        _url = _server.Register(_file, "video/mp4", IPAddress.Loopback);
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _http.Dispose();
        File.Delete(_file);
    }

    [Fact]
    public async Task Range_request_returns_206_with_exact_bytes()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, _url);
        request.Headers.Range = new RangeHeaderValue(5000, 5009);

        using var response = await _http.SendAsync(request);
        var body = await response.Content.ReadAsByteArrayAsync();

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal(10_000, response.Content.Headers.ContentRange!.Length);
        Assert.Equal(Enumerable.Range(5000, 10).Select(i => (byte)(i % 251)), body);
        Assert.Contains("bytes", response.Headers.AcceptRanges);
    }

    [Fact]
    public async Task Whole_file_without_range()
    {
        var body = await _http.GetByteArrayAsync(_url);

        Assert.Equal(10_000, body.Length);
    }

    [Fact]
    public async Task Wrong_token_and_unknown_file_are_plain_404s()
    {
        var parts = _url.Split('/');
        var wrongToken = string.Join('/', parts[..^2]) + "/AAAAAAAAAAAAAAAAAAAAAA/" + parts[^1];
        var unknownFile = string.Join('/', parts[..^1]) + "/nope";

        Assert.Equal(HttpStatusCode.NotFound, (await _http.GetAsync(wrongToken)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await _http.GetAsync(unknownFile)).StatusCode);
    }

    [Fact]
    public async Task Ending_the_session_revokes_old_urls()
    {
        _server.EndSession();

        Assert.Equal(HttpStatusCode.NotFound, (await _http.GetAsync(_url)).StatusCode);
    }
}
