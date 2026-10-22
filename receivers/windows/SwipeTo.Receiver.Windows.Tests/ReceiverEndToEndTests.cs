using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using SwipeTo.MockSender;
using Xunit.Abstractions;

namespace SwipeTo.Receiver.Windows.Tests;

/// <summary>
/// Phase 2 acceptance against the real Windows receiver: the mock sender pairs, hands off a real MP4,
/// and drives pause/seek/play/pull-back. Run serially: one receiver owns the screen at a time.
/// </summary>
[Collection("screen")]
public sealed partial class ReceiverEndToEndTests(ITestOutputHelper output) : IAsyncLifetime
{
    private const long HandoffAtMs = 42_000;
    private readonly string _senderState = Path.Combine(Path.GetTempPath(), $"swipeto-e2e-sender-{Guid.NewGuid():N}.json");
    private ReceiverProcess _receiver = null!;
    private string _video = "";

    public async Task InitializeAsync()
    {
        if (Environment.GetEnvironmentVariable("SWIPETO_E2E") != "1") return;
        _video = await SampleVideo.GetAsync();
        _receiver = await ReceiverProcess.StartAsync();
    }

    public async Task DisposeAsync()
    {
        if (_receiver is not null)
        {
            output.WriteLine("--- receiver log ---\n" + _receiver.Log);
            await _receiver.DisposeAsync();
        }
        File.Delete(_senderState);
    }

    private MockSenderApp Sender(string? receiver = null, TimeSpan? aimHold = null, TimeSpan? prefetch = null) => new(new MockSenderOptions
    {
        Receiver = receiver ?? _receiver.ReceiverId,
        Code = ReceiverProcess.PairingCode,
        File = _video,
        DurationMs = SampleVideo.DurationMs,
        PositionMs = HandoffAtMs,
        StateFile = _senderState,
        AimHold = aimHold ?? TimeSpan.Zero,
        PrefetchLead = prefetch ?? TimeSpan.Zero,
        StepDelay = TimeSpan.FromMilliseconds(1500), // let playback actually start before each control step
        DiscoveryTimeout = TimeSpan.FromSeconds(15),
    }, new TestWriter(output));

    [E2EFact]
    public async Task Pair_handoff_control_and_pullback_over_mdns()
    {
        await using var sender = Sender(aimHold: TimeSpan.FromSeconds(2));

        Assert.True(await sender.RunScriptAsync());

        Assert.All(sender.Steps, s => Assert.True(s.Ok, s.Step));
        Assert.Contains(sender.Steps, s => s.Step == "discover");
        await _receiver.WaitForLogAsync(AimShown());
        await _receiver.WaitForLogAsync(AimHidden());

        // "Playback starts within ~1 s at the requested position (±300 ms)."
        var first = await _receiver.WaitForLogAsync(FirstFrame());
        var startMs = long.Parse(first.Groups["ms"].Value, CultureInfo.InvariantCulture);
        var atMs = long.Parse(first.Groups["at"].Value, CultureInfo.InvariantCulture);
        output.WriteLine($"Handoff to first frame: {startMs} ms, at {atMs} ms");
        Assert.InRange(startMs, 0, 1200);
        Assert.InRange(atMs, HandoffAtMs - 300, HandoffAtMs + 300);
    }

    [E2EFact]
    public async Task Survives_sender_disconnect_and_accepts_a_new_session()
    {
        var first = Sender(_receiver.LocalUri.ToString());
        await first.ConnectAsync();
        await first.HandoffAsync();
        await first.DisposeAsync(); // phone vanishes mid-playback

        await using var again = Sender(_receiver.LocalUri.ToString());
        Assert.True(await again.RunScriptAsync());
    }

    [E2EFact]
    public async Task Closing_the_player_on_the_screen_tells_the_phone()
    {
        await using var sender = Sender(_receiver.LocalUri.ToString());
        await sender.ConnectAsync();
        await sender.HandoffAsync();
        await sender.Client.WaitForStateAsync(s => s.Playing, TimeSpan.FromSeconds(10));

        var ended = sender.Client.WaitForStateAsync(s => s.Ended, TimeSpan.FromSeconds(10));
        _receiver.ClosePlayerWindow();

        Assert.False((await ended).Playing);
        await _receiver.WaitForLogAsync(new Regex("Player closed by the viewer"));
    }

    [E2EFact]
    public async Task Prefetch_on_aim_starts_faster_than_a_cold_handoff()
    {
        // Same machine and clock for both sides; over Wi-Fi the gap is larger (see the PR's A15 numbers).
        await using (var cold = Sender(_receiver.LocalUri.ToString()))
        {
            Assert.True(await cold.RunScriptAsync());
        }
        await using (var warm = Sender(_receiver.LocalUri.ToString(), prefetch: TimeSpan.FromMilliseconds(1500)))
        {
            Assert.True(await warm.RunScriptAsync());
        }

        var lines = FirstFrameDetailed().Matches(_receiver.Log);
        var coldMs = lines.Where(m => m.Groups["how"].Value == "cold").Select(m => long.Parse(m.Groups["ms"].Value, CultureInfo.InvariantCulture)).First();
        var warmMs = lines.Where(m => m.Groups["how"].Value == "PREFETCHED").Select(m => long.Parse(m.Groups["ms"].Value, CultureInfo.InvariantCulture)).First();
        output.WriteLine($"Handoff to first frame: cold {coldMs} ms, prefetched {warmMs} ms");

        Assert.True(warmMs < 500, $"prefetched start {warmMs} ms");
        Assert.True(warmMs <= coldMs, $"prefetched {warmMs} ms vs cold {coldMs} ms");
    }

    [GeneratedRegex(@"First frame playing (?<ms>\d+) ms after handoff at \d+ ms, (?<how>cold|PREFETCHED)")]
    private static partial Regex FirstFrameDetailed();

    [GeneratedRegex("Aim glow and marker shown")]
    private static partial Regex AimShown();

    [GeneratedRegex("Aim glow and marker hidden")]
    private static partial Regex AimHidden();

    [GeneratedRegex(@"First frame playing (?<ms>\d+) ms after handoff at (?<at>\d+) ms")]
    private static partial Regex FirstFrame();

    private sealed class TestWriter(ITestOutputHelper output) : TextWriter
    {
        public override Encoding Encoding => Encoding.UTF8;

        public override void WriteLine(string? value)
        {
            try { output.WriteLine(value ?? ""); } catch (InvalidOperationException) { }
        }
    }
}
