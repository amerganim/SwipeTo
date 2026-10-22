using System.Net;
using SwipeTo.Net.Receiver;
using SwipeTo.Net.Sender;

namespace SwipeTo.Tools.Tests;

public sealed class HttpRangeStreamTests : IAsyncLifetime
{
    private const int FileSize = 1_234_567; // not a multiple of the block size
    private readonly MediaServer _server = new();
    private readonly HttpClient _http = new();
    private readonly string _file = Path.GetTempFileName();
    private byte[] _data = [];
    private Uri _url = null!;

    public async Task InitializeAsync()
    {
        _data = new byte[FileSize];
        new Random(7).NextBytes(_data);
        await File.WriteAllBytesAsync(_file, _data);
        await _server.StartAsync();
        _url = new Uri(_server.Register(_file, "video/mp4", IPAddress.Loopback));
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _http.Dispose();
        File.Delete(_file);
    }

    [Fact]
    public async Task Reports_length_and_reads_the_whole_file_sequentially()
    {
        await using var stream = await HttpRangeStream.OpenAsync(_http, _url, blockSize: 64 * 1024);
        using var copy = new MemoryStream();

        await stream.CopyToAsync(copy);

        Assert.Equal(FileSize, stream.Length);
        Assert.Equal(_data, copy.ToArray());
    }

    [Theory]
    [InlineData(0, 10)]
    [InlineData(65_530, 20)] // spans a block boundary
    [InlineData(FileSize - 7, 100)] // runs past the end
    [InlineData(700_000, 300_000)]
    public async Task Random_reads_return_the_right_bytes(long offset, int count)
    {
        await using var stream = await HttpRangeStream.OpenAsync(_http, _url, blockSize: 64 * 1024);
        stream.Seek(offset, SeekOrigin.Begin);

        var buffer = new byte[count];
        var read = await stream.ReadAtLeastAsync(buffer, count, throwOnEndOfStream: false);

        var expected = (int)Math.Min(count, FileSize - offset);
        Assert.Equal(expected, read);
        Assert.Equal(_data.AsSpan((int)offset, expected).ToArray(), buffer[..read]);
        Assert.Equal(offset + read, stream.Position);
    }

    [Fact]
    public async Task Seek_from_end_and_read_past_end()
    {
        await using var stream = await HttpRangeStream.OpenAsync(_http, _url);
        stream.Seek(-3, SeekOrigin.End);

        var buffer = new byte[10];
        Assert.Equal(3, await stream.ReadAsync(buffer));
        Assert.Equal(0, await stream.ReadAsync(buffer));
    }

    [Fact]
    public async Task A_network_blip_while_reading_is_retried_not_fatal()
    {
        using var flaky = new HttpClient(new FailingHandler { InnerHandler = new HttpClientHandler() });
        await using var stream = await HttpRangeStream.OpenAsync(flaky, _url, blockSize: 64 * 1024);
        FailingHandler.FailNext = 3; // the phone's Wi-Fi is off for a moment
        var buffer = new byte[1000];

        stream.Position = 500_000;
        var read = await stream.ReadAsync(buffer);

        Assert.Equal(_data.AsSpan(500_000, read).ToArray(), buffer[..read]);
        Assert.Equal(0, FailingHandler.FailNext);
    }

    [Fact]
    public async Task A_refusing_server_is_not_retried()
    {
        await using var stream = await HttpRangeStream.OpenAsync(_http, _url, blockSize: 64 * 1024);
        await _server.DisposeAsync(); // everything after this is refused or unreachable
        stream.RetryWindow = TimeSpan.FromSeconds(1);
        stream.Position = 500_000;

        await Assert.ThrowsAnyAsync<Exception>(async () => await stream.ReadAsync(new byte[10]));
    }

    private sealed class FailingHandler : DelegatingHandler
    {
        public static int FailNext;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (FailNext <= 0) return base.SendAsync(request, ct);
            FailNext--;
            throw new HttpRequestException("Network is unreachable");
        }
    }

    [Fact]
    public async Task Unknown_url_fails_to_open()
    {
        var bad = new Uri(_url, "/m/nope/nope");

        await Assert.ThrowsAsync<HttpRequestException>(() => HttpRangeStream.OpenAsync(_http, bad));
    }
}
