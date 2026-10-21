using SwipeTo.MockReceiver;
using SwipeTo.Net.Receiver;
using SwipeTo.Net.Sender;
using SwipeTo.Protocol;

namespace SwipeTo.Tools.Tests;

/// <summary>Marker rotation and lock verification (docs/protocol.md, section 5.3).</summary>
public sealed class AimLockTests : IAsyncLifetime
{
    private const string Code = "135790";
    private readonly string _dir = Directory.CreateTempSubdirectory("swipeto-aim-").FullName;
    private MockReceiverApp _receiver = null!;
    private SenderClient _client = null!;

    public async Task InitializeAsync()
    {
        _receiver = await MockReceiverApp.StartAsync(new MockReceiverOptions
        {
            ReceiverId = Guid.NewGuid().ToString("D"),
            Port = 0,
            PairingCode = Code,
            Advertise = false,
            TrustFile = Path.Combine(_dir, "trust.json"),
            MarkerRotation = TimeSpan.FromMilliseconds(300),
        });
        _client = await SenderClient.ConnectAsync(new Uri($"ws://127.0.0.1:{_receiver.Host.Port}/v1"), new SenderIdentity(Guid.NewGuid().ToString("D"), "Aimer"));
        var token = await _client.PairAsync(_ => Task.FromResult(Code));
        Assert.True((await _client.HelloAsync(token!)).Accepted);
    }

    public async Task DisposeAsync()
    {
        await _client.DisposeAsync();
        await _receiver.DisposeAsync();
        Directory.Delete(_dir, recursive: true);
    }

    private async Task<string> ShownNonceAsync()
    {
        for (var i = 0; i < 50 && _receiver.AimMarker is null; i++) await Task.Delay(20);
        var parts = _receiver.AimMarker!.Split(':');
        Assert.Equal("swipeto", parts[0]);
        Assert.Equal(_receiver.Host.Options.ReceiverId, parts[1]);
        return parts[2];
    }

    [Fact]
    public async Task Reading_the_shown_marker_locks_the_screen()
    {
        await _client.AimStartAsync();
        var nonce = await ShownNonceAsync();
        Assert.False(_receiver.AimLocked);

        var ack = await _client.LockAsync(nonce);

        Assert.True(ack.Locked);
        Assert.True(_receiver.AimLocked);
    }

    [Fact]
    public async Task Marker_rotates_and_old_reads_go_stale()
    {
        await _client.AimStartAsync();
        var first = await ShownNonceAsync();

        await Task.Delay(400); // one rotation: the previous nonce still counts
        Assert.NotEqual(first, await ShownNonceAsync());
        Assert.True((await _client.LockAsync(first)).Locked);

        await Task.Delay(700); // two more rotations: a photo of the first marker is now useless
        Assert.False((await _client.LockAsync(first)).Locked);
    }

    [Fact]
    public async Task Unknown_nonce_is_refused()
    {
        await _client.AimStartAsync();
        await ShownNonceAsync();

        Assert.False((await _client.LockAsync("AAAAAAAAAAAAAAAAAAAA")).Locked);
        Assert.False(_receiver.AimLocked);
    }

    [Fact]
    public async Task Aim_end_hides_and_clears_the_lock()
    {
        await _client.AimStartAsync();
        Assert.True((await _client.LockAsync(await ShownNonceAsync())).Locked);

        await _client.AimEndAsync();
        for (var i = 0; i < 50 && _receiver.AimMarker is not null; i++) await Task.Delay(20);

        Assert.Null(_receiver.AimMarker);
        Assert.False(_receiver.AimLocked);
        await Task.Delay(700); // rotation must not bring it back
        Assert.Null(_receiver.AimMarker);
    }

    [Fact]
    public void Marker_nonces_accept_current_and_previous_only()
    {
        var markers = new MarkerNonces();
        var a = markers.Current;
        markers.Rotate();
        var b = markers.Current;

        Assert.True(markers.IsValid(a));
        Assert.True(markers.IsValid(b));
        markers.Rotate();
        Assert.False(markers.IsValid(a));
        Assert.True(markers.IsValid(b));
        Assert.Matches("^[A-Za-z0-9_-]{16}$", markers.Current);
    }
}
