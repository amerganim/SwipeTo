using SwipeTo.Net.Sender;
using SwipeTo.Protocol;
using Xunit.Abstractions;

namespace SwipeTo.Receiver.Windows.Tests;

/// <summary>
/// App content against the real receiver and YouTube's real player (needs internet): a handoff by
/// video id plays at the handed-off second, pauses, seeks and comes back. By title it needs the
/// user's API key, which tests never have: there the receiver must refuse, not guess.
/// </summary>
[Collection("screen")]
public sealed class YouTubeEndToEndTests(ITestOutputHelper output) : IAsyncLifetime
{
    /// <summary>"Big Buck Bunny 60fps 4K", Blender Foundation (CC-BY), embeddable.</summary>
    private static readonly MediaInfo Bunny = MediaInfo.App("youtube", "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", "Blender", contentId: "aqz-KE-bpKQ");

    private ReceiverProcess _receiver = null!;
    private SenderClient _phone = null!;

    public async Task InitializeAsync()
    {
        if (Environment.GetEnvironmentVariable("SWIPETO_E2E") != "1") return;
        _receiver = await ReceiverProcess.StartAsync();
        _phone = await SenderClient.ConnectAsync(_receiver.LocalUri, new SenderIdentity(Guid.NewGuid().ToString("D"), "YouTube test phone"));
        var token = await _phone.PairAsync(_ => Task.FromResult(ReceiverProcess.PairingCode));
        Assert.True((await _phone.HelloAsync(token!)).Accepted);
    }

    public async Task DisposeAsync()
    {
        if (_receiver is null) return;
        output.WriteLine("--- receiver log ---\n" + _receiver.Log);
        await _phone.DisposeAsync();
        await _receiver.DisposeAsync();
    }

    [E2EFact]
    public async Task A_video_by_id_plays_at_the_handed_off_second_and_is_a_remote()
    {
        Assert.True((await _phone.HandoffAsync(Bunny, 60_000, playing: true)).Accepted);

        var shown = await _phone.WaitForStateAsync(s => s.Playing && !s.Buffering, TimeSpan.FromSeconds(30));
        output.WriteLine($"YouTube showing frames at {shown.PositionMs} ms");
        Assert.InRange(shown.PositionMs, 59_000, 64_000);

        await _phone.ControlAsync(ControlAction.Pause);
        var paused = await _phone.WaitForStateAsync(s => !s.Playing, TimeSpan.FromSeconds(10));
        await _phone.ControlAsync(ControlAction.Seek, 120_000);
        await Task.Delay(2_000);
        var back = await _phone.PullbackAsync();

        output.WriteLine($"paused at {paused.PositionMs} ms, pulled back at {back.PositionMs} ms");
        Assert.InRange(back.PositionMs, 119_000, 122_000);
    }

    [E2EFact]
    public async Task By_title_without_an_api_key_the_screen_refuses()
    {
        var byTitle = Bunny with { ContentId = null };

        var ack = await _phone.HandoffAsync(byTitle, 0, playing: true);

        Assert.False(ack.Accepted);
        Assert.Equal(ErrorCode.ServiceUnavailable, ack.Reason);
    }
}
