using System.Net;
using System.Net.WebSockets;
using System.Text;
using SwipeTo.MockReceiver;
using SwipeTo.MockSender;
using SwipeTo.Net.Sender;
using SwipeTo.Protocol;
using Xunit.Abstractions;

namespace SwipeTo.Tools.Tests;

/// <summary>Runs the mock sender against the mock receiver on this machine (Phase 1 acceptance).</summary>
public sealed class MockFlowTests(ITestOutputHelper output) : IAsyncLifetime
{
    private const string Code = "482913";
    private readonly string _dir = Directory.CreateTempSubdirectory("swipeto-test-").FullName;
    private readonly string _receiverId = Guid.NewGuid().ToString("D");
    private MockReceiverApp _receiver = null!;

    public async Task InitializeAsync()
    {
        _receiver = await MockReceiverApp.StartAsync(new MockReceiverOptions
        {
            Name = "Test screen " + _receiverId[..4],
            ReceiverId = _receiverId,
            Port = 0,
            PairingCode = Code,
            TrustFile = Path.Combine(_dir, "trust.json"),
        });
    }

    public async Task DisposeAsync()
    {
        await _receiver.DisposeAsync();
        Directory.Delete(_dir, recursive: true);
    }

    private Uri LocalUri => new($"ws://127.0.0.1:{_receiver.Host.Port}/v1");

    private MockSenderOptions SenderOptions(string? receiver = null, string? code = Code, string state = "state.json") => new()
    {
        Receiver = receiver ?? _receiverId,
        Code = code,
        StateFile = Path.Combine(_dir, state),
        DiscoveryTimeout = TimeSpan.FromSeconds(15),
    };

    [Fact]
    public async Task Full_flow_over_mdns_discover_pair_hello_handoff_control_pullback()
    {
        await using var sender = new MockSenderApp(SenderOptions(), new TestWriter(output));

        Assert.True(await sender.RunScriptAsync());

        Assert.Equal(
            ["discover", "connect", "pair", "hello", "handoff", "playing at position", "pause", "seek", "play", "pull-back"],
            sender.Steps.Select(s => s.Step));
        Assert.All(sender.Steps, s => Assert.True(s.Ok, s.Step));
        Assert.Equal(_receiverId, sender.Receiver!.Id);
        Assert.Equal([HttpStatusCode.PartialContent, HttpStatusCode.PartialContent], _receiver.Playback.RangeResponses);
        Assert.Null(_receiver.AimMarker);
        Assert.Single(_receiver.Host.Trust.List());
    }

    [Fact]
    public async Task Second_session_reuses_the_trust_token()
    {
        await using (var first = new MockSenderApp(SenderOptions(LocalUri.ToString()), new TestWriter(output)))
        {
            Assert.True(await first.RunScriptAsync());
        }

        await using var second = new MockSenderApp(SenderOptions(LocalUri.ToString(), code: null), new TestWriter(output));
        Assert.True(await second.RunScriptAsync());

        Assert.DoesNotContain(second.Steps, s => s.Step == "pair");
    }

    [Fact]
    public async Task Unknown_token_is_refused_as_not_paired()
    {
        await using var client = await SenderClient.ConnectAsync(LocalUri, new SenderIdentity(Guid.NewGuid().ToString("D"), "Stranger"));

        var ack = await client.HelloAsync("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        Assert.False(ack.Accepted);
        Assert.Equal(ErrorCode.NotPaired, ack.Reason);
    }

    [Fact]
    public async Task Three_wrong_codes_end_pairing()
    {
        await using var client = await SenderClient.ConnectAsync(LocalUri, new SenderIdentity(Guid.NewGuid().ToString("D"), "Guesser"));

        var token = await client.PairAsync(_ => Task.FromResult("000000"));

        Assert.Null(token);
        Assert.Equal(SessionState.Connected, client.State);
        Assert.Empty(_receiver.Host.Trust.List());
    }

    [Fact]
    public async Task Handoff_before_hello_gets_not_paired()
    {
        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(LocalUri, CancellationToken.None);
        var handoff = new Handoff(new MediaInfo(MediaKind.Video, "http://127.0.0.1:1/m/x/y", "video/mp4", "x"), 0, true);
        await socket.SendAsync(Encoding.UTF8.GetBytes(MessageCodec.Serialize(handoff)), WebSocketMessageType.Text, true, CancellationToken.None);

        var buffer = new byte[4096];
        var received = await socket.ReceiveAsync(buffer, CancellationToken.None);
        var reply = Assert.IsType<ErrorMessage>(MessageCodec.Parse(buffer.AsSpan(0, received.Count)));

        Assert.Equal(ErrorCode.NotPaired, reply.Code);
        Assert.Equal(handoff.Id, reply.ReplyTo);
    }

    [Fact]
    public async Task Second_sender_is_busy_while_the_first_has_a_handoff()
    {
        await using var first = new MockSenderApp(SenderOptions(LocalUri.ToString(), state: "a.json"), new TestWriter(output));
        await first.ConnectAsync();
        await first.HandoffAsync();

        await using var second = new MockSenderApp(SenderOptions(LocalUri.ToString(), state: "b.json"), new TestWriter(output));
        var error = await Assert.ThrowsAsync<InvalidOperationException>(second.ConnectAsync);

        Assert.Contains("Busy", error.Message);
    }

    [Fact]
    public async Task Closing_on_the_screen_tells_the_phone_playback_ended()
    {
        await using var sender = new MockSenderApp(SenderOptions(LocalUri.ToString()), new TestWriter(output));
        await sender.ConnectAsync();
        await sender.HandoffAsync();
        // handoff_ack comes before the media loads; the first state means it is playing.
        if (sender.Client.LastState is null) await sender.Client.WaitForStateAsync(_ => true);

        var ended = sender.Client.WaitForStateAsync(s => s.Ended);
        _receiver.Playback.Stop();

        Assert.False((await ended).Playing);
        await Task.Delay(100);
        Assert.Equal(SessionState.Session, sender.Client.State);
    }

    [Fact]
    public async Task Survives_a_sender_disconnect_and_accepts_a_new_session()
    {
        var first = new MockSenderApp(SenderOptions(LocalUri.ToString()), new TestWriter(output));
        await first.ConnectAsync();
        await first.HandoffAsync();
        await first.DisposeAsync();

        await using var again = new MockSenderApp(SenderOptions(LocalUri.ToString(), code: null), new TestWriter(output));
        Assert.True(await again.RunScriptAsync());
    }

    private sealed class TestWriter(ITestOutputHelper output) : TextWriter
    {
        public override Encoding Encoding => Encoding.UTF8;

        public override void WriteLine(string? value)
        {
            try { output.WriteLine(value ?? ""); } catch (InvalidOperationException) { /* test already finished */ }
        }
    }
}
