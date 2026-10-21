using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using SwipeTo.Net;
using SwipeTo.Net.Sender;
using SwipeTo.Protocol;

namespace SwipeTo.MockSender;

public sealed record MockSenderOptions
{
    /// <summary>Receiver id to discover, a <c>ws://</c> URL to connect to directly, or null for the first receiver found.</summary>
    public string? Receiver { get; init; }

    public string Name { get; init; } = "Mock phone";
    public string? File { get; init; }
    public string Mime { get; init; } = "video/mp4";
    public string Title { get; init; } = "Sample video";
    public long PositionMs { get; init; } = 42_000;
    public long DurationMs { get; init; } = 600_000;
    public long SeekToMs { get; init; } = 120_000;

    /// <summary>Pairing code to type; null asks on the console.</summary>
    public string? Code { get; init; }

    public string StateFile { get; init; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SwipeTo", "mock-sender", "state.json");

    public TimeSpan DiscoveryTimeout { get; init; } = TimeSpan.FromSeconds(10);
}

public sealed record StepResult(string Step, bool Ok, string Detail, long ElapsedMs);

/// <summary>
/// Acts like a phone: discovers a receiver, pairs if needed, says hello, serves a file over HTTP with
/// Range support, hands it off, and drives remote control and pull-back.
/// </summary>
public sealed class MockSenderApp : IAsyncDisposable
{
    private readonly MockSenderOptions _options;
    private readonly TextWriter _out;
    private readonly SenderState _state;
    private readonly MediaServer _media = new();
    private SenderClient? _client;
    private string? _trustKey;

    public MockSenderApp(MockSenderOptions options, TextWriter? output = null)
    {
        _options = options;
        _out = output ?? Console.Out;
        _state = SenderState.Load(options.StateFile);
    }

    public SenderClient Client => _client ?? throw new InvalidOperationException("Not connected");

    public List<StepResult> Steps { get; } = [];

    public DiscoveredReceiver? Receiver { get; private set; }

    public async ValueTask DisposeAsync()
    {
        if (_client is not null) await _client.DisposeAsync();
        await _media.DisposeAsync();
    }

    /// <summary>Discover → connect → pair if needed → hello. Returns the endpoint used.</summary>
    public async Task<Uri> ConnectAsync()
    {
        IEnumerable<Uri> candidates;
        if (_options.Receiver is { } r && r.StartsWith("ws://", StringComparison.OrdinalIgnoreCase))
        {
            candidates = [new Uri(r)];
            _trustKey = r;
        }
        else
        {
            Receiver = await Step("discover", async () =>
            {
                var found = _options.Receiver is { } id
                    ? await ReceiverBrowser.FindAsync(id, _options.DiscoveryTimeout)
                    : (await ReceiverBrowser.BrowseAsync(TimeSpan.FromSeconds(3))).FirstOrDefault();
                return found is null
                    ? throw new TimeoutException("No receiver found over mDNS")
                    : (found, $"{found.Name} ({found.Id}) at {string.Join(", ", found.Addresses)}:{found.Port}");
            });
            candidates = Receiver.WebSocketUris();
            _trustKey = Receiver.Id;
        }

        var identity = new SenderIdentity(_state.SenderId, _options.Name);
        var uri = await Step("connect", async () =>
        {
            foreach (var candidate in candidates)
            {
                try
                {
                    _client = await SenderClient.ConnectAsync(candidate, identity);
                    return (candidate, candidate.ToString());
                }
                catch (Exception e) when (e is WebSocketException or OperationCanceledException)
                {
                    _out.WriteLine($"  {candidate} unreachable: {e.Message}");
                }
            }
            throw new WebSocketException("No address of the receiver accepted a connection");
        });
        Client.StateReceived += s => _out.WriteLine($"  state: {s.PositionMs} ms, {(s.Playing ? "playing" : "paused")}{(s.Ended ? ", ended" : "")}");
        Client.ErrorReceived += e => _out.WriteLine($"  error from receiver: {e.Code} {e.Description}");

        var ack = _state.TrustTokens.TryGetValue(_trustKey, out var saved) ? await Client.HelloAsync(saved) : null;
        if (ack is null || ack is { Accepted: false, Reason: ErrorCode.NotPaired })
        {
            var token = await Step("pair", async () =>
            {
                var issued = await Client.PairAsync(AskForCodeAsync);
                return issued is null ? throw new InvalidOperationException("Pairing failed") : (issued, "paired, token stored");
            });
            _state.TrustTokens[_trustKey] = token;
            _state.Save(_options.StateFile);
            ack = await Client.HelloAsync(token);
        }
        await Step("hello", () => Task.FromResult(ack.Accepted
            ? (ack, $"accepted by {ack.ReceiverId}")
            : throw new InvalidOperationException($"hello refused: {ack.Reason}")));
        return uri;
    }

    /// <summary>Starts the media server and hands the file off at <see cref="MockSenderOptions.PositionMs"/>.</summary>
    public async Task HandoffAsync(bool playing = true)
    {
        await _media.StartAsync();
        var file = _options.File ?? CreateSampleFile();
        var local = MediaServer.LocalAddressFor(Receiver?.Addresses.FirstOrDefault(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
            ?? IPAddress.Loopback);
        var media = new MediaInfo(_options.Mime.StartsWith("image/", StringComparison.Ordinal) ? MediaKind.Image : MediaKind.Video,
            _media.Register(file, _options.Mime, local), _options.Mime, _options.Title, _options.DurationMs);

        await Client.AimStartAsync();
        await Step("handoff", async () =>
        {
            var ack = await Client.HandoffAsync(media, _options.PositionMs, playing);
            return ack.Accepted ? (ack, $"accepted, media at {local}:{_media.Port}") : throw new InvalidOperationException($"refused: {ack.Reason}");
        });
    }

    /// <summary>The automated flow used by tests and <c>--script</c>: handoff, pause, seek, play, pull-back.</summary>
    public async Task<bool> RunScriptAsync()
    {
        try
        {
            await ConnectAsync();
            await HandoffAsync(playing: true);
            await Step("playing at position", () => WaitState(s => s.Playing && s.PositionMs >= _options.PositionMs - 300 && s.PositionMs <= _options.PositionMs + 3000));

            await Client.ControlAsync(ControlAction.Pause);
            await Step("pause", () => WaitState(s => !s.Playing));

            await Client.ControlAsync(ControlAction.Seek, _options.SeekToMs);
            await Step("seek", () => WaitState(s => !s.Playing && s.PositionMs == _options.SeekToMs));

            await Client.ControlAsync(ControlAction.Play);
            await Step("play", () => WaitState(s => s.Playing && s.PositionMs >= _options.SeekToMs));

            await Step("pull-back", async () =>
            {
                var back = await Client.PullbackAsync();
                return back.Playing && back.PositionMs >= _options.SeekToMs && back.PositionMs < _options.SeekToMs + 5000
                    ? (back, $"returned {back.PositionMs} ms, playing")
                    : throw new InvalidOperationException($"unexpected pull-back {back.PositionMs} ms playing={back.Playing}");
            });
            return true;
        }
        catch (Exception e)
        {
            _out.WriteLine($"FAILED: {e.Message}");
            return false;
        }
    }

    private async Task<(PlaybackState, string)> WaitState(Func<PlaybackState, bool> predicate)
    {
        var state = Client.LastState is { } last && predicate(last) ? last : await Client.WaitForStateAsync(predicate);
        return (state, $"{state.PositionMs} ms, {(state.Playing ? "playing" : "paused")}");
    }

    private async Task<T> Step<T>(string name, Func<Task<(T Value, string Detail)>> action)
    {
        var clock = Stopwatch.StartNew();
        try
        {
            var (value, detail) = await action();
            Steps.Add(new StepResult(name, true, detail, clock.ElapsedMilliseconds));
            _out.WriteLine($"ok   {name,-20} {detail} ({clock.ElapsedMilliseconds} ms)");
            return value;
        }
        catch (Exception e)
        {
            Steps.Add(new StepResult(name, false, e.Message, clock.ElapsedMilliseconds));
            _out.WriteLine($"FAIL {name,-20} {e.Message}");
            throw;
        }
    }

    private Task<string> AskForCodeAsync(int attempt)
    {
        if (_options.Code is { } code) return Task.FromResult(code);
        Console.Write(attempt == 1 ? "Enter the 6-digit code shown on the screen: " : "Wrong code, try again: ");
        return Task.FromResult(Console.ReadLine()?.Trim() ?? "");
    }

    /// <summary>A few MB of bytes served as video/mp4: enough for Range tests, not playable by a real player.</summary>
    private static string CreateSampleFile()
    {
        var path = Path.Combine(Path.GetTempPath(), "swipeto-mock-sample.mp4");
        if (!System.IO.File.Exists(path))
        {
            var data = new byte[3 * 1024 * 1024];
            new Random(42).NextBytes(data);
            System.IO.File.WriteAllBytes(path, data);
        }
        return path;
    }
}
