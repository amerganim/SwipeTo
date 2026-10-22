using System.Net;
using SwipeTo.MockReceiver;
using SwipeTo.Net.Sender;
using SwipeTo.Protocol;

namespace SwipeTo.Tools.Tests;

/// <summary>Prefetch on aim, same-media re-attach and album transitions (docs/protocol.md, 5.3 and 5.4).</summary>
public sealed class PrefetchTests : IAsyncLifetime
{
    private const string Code = "975310";
    private readonly string _dir = Directory.CreateTempSubdirectory("swipeto-prefetch-").FullName;
    private readonly string _file = Path.GetTempFileName();
    private readonly MediaServer _media = new();
    private MockReceiverApp _receiver = null!;
    private SenderClient _client = null!;
    private MediaInfo _video = null!;

    public async Task InitializeAsync()
    {
        await File.WriteAllBytesAsync(_file, new byte[200_000]);
        await _media.StartAsync();
        _video = new MediaInfo(MediaKind.Video, _media.Register(_file, "video/mp4", IPAddress.Loopback), "video/mp4", "clip", 600_000);
        _receiver = await MockReceiverApp.StartAsync(new MockReceiverOptions
        {
            ReceiverId = Guid.NewGuid().ToString("D"),
            Port = 0,
            PairingCode = Code,
            Advertise = false,
            TrustFile = Path.Combine(_dir, "trust.json"),
        });
        _client = await SenderClient.ConnectAsync(new Uri($"ws://127.0.0.1:{_receiver.Host.Port}/v1"), new SenderIdentity(Guid.NewGuid().ToString("D"), "Prefetcher"));
        Assert.True((await _client.HelloAsync((await _client.PairAsync(_ => Task.FromResult(Code)))!)).Accepted);
    }

    public async Task DisposeAsync()
    {
        await _client.DisposeAsync();
        await _receiver.DisposeAsync();
        await _media.DisposeAsync();
        File.Delete(_file);
        Directory.Delete(_dir, recursive: true);
    }

    private async Task Until(Func<bool> condition)
    {
        for (var i = 0; i < 100 && !condition(); i++) await Task.Delay(20);
        Assert.True(condition());
    }

    [Fact]
    public async Task Aim_with_prefetch_starts_loading_and_the_handoff_uses_it()
    {
        await _client.AimStartAsync(new Prefetch(_video, 42_000));
        await Until(() => _receiver.Playback.RangeResponses.Count == 1); // fetched while still aiming

        Assert.True((await _client.HandoffAsync(_video, 43_500, playing: true)).Accepted);
        await _client.WaitForStateAsync(s => s.Playing);

        Assert.Equal(1, _receiver.Playback.PrefetchHits);
        Assert.Single(_receiver.Playback.RangeResponses); // nothing fetched again at handoff
    }

    [Fact]
    public async Task Repeated_aim_with_the_same_media_does_not_fetch_again()
    {
        await _client.AimStartAsync(new Prefetch(_video, 42_000));
        await _client.AimStartAsync(new Prefetch(_video, 46_000));
        await _client.AimStartAsync(new Prefetch(_video, 50_000));
        await Task.Delay(300);

        Assert.Single(_receiver.Playback.RangeResponses);
    }

    [Fact]
    public async Task Aim_end_discards_the_prefetch()
    {
        await _client.AimStartAsync(new Prefetch(_video, 42_000));
        await Until(() => _receiver.Playback.Prefetched is not null);

        await _client.AimEndAsync();

        await Until(() => _receiver.Playback.Prefetched is null);
    }

    [Fact]
    public async Task Handing_off_the_same_media_again_reattaches_without_reloading()
    {
        Assert.True((await _client.HandoffAsync(_video, 42_000, playing: true)).Accepted);
        await _client.WaitForStateAsync(s => s.Playing);
        var fetches = _receiver.Playback.RangeResponses.Count;

        // A phone that reconnected hands off again at its estimate: close enough, no seek, no reload.
        Assert.True((await _client.HandoffAsync(_video, 42_500, playing: true)).Accepted);
        var state = await _client.WaitForStateAsync(s => s.Playing);

        Assert.Equal(fetches, _receiver.Playback.RangeResponses.Count);
        Assert.InRange(state.PositionMs, 42_000, 44_000);

        // Far off: seek, still without reloading.
        Assert.True((await _client.HandoffAsync(_video, 90_000, playing: false)).Accepted);
        var moved = await _client.WaitForStateAsync(s => !s.Playing && s.PositionMs == 90_000);
        Assert.Equal(fetches, _receiver.Playback.RangeResponses.Count);
        Assert.False(moved.Ended);
    }

    [Fact]
    public async Task Album_handoffs_carry_their_transition()
    {
        var photo = new MediaInfo(MediaKind.Image, _media.Register(_file, "image/jpeg", IPAddress.Loopback), "image/jpeg", "IMG_1");
        var next = photo with { Url = _media.Register(_file, "image/jpeg", IPAddress.Loopback), Title = "IMG_2" };
        Assert.True((await _client.HandoffAsync(photo, 0, playing: false)).Accepted);
        await _client.WaitForStateAsync(_ => true);

        Assert.True((await _client.HandoffAsync(next, 0, playing: false, Transition.Next)).Accepted);
        await Until(() => _receiver.Playback.Current?.Title == "IMG_2");

        Assert.Equal(Transition.Next, _receiver.Playback.LastTransition);
    }
}
