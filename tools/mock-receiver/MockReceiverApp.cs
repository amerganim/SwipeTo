using System.Text.Json;
using Microsoft.Extensions.Logging;
using SwipeTo.Net.Receiver;

namespace SwipeTo.MockReceiver;

public sealed record MockReceiverOptions
{
    public string Name { get; init; } = "Mock receiver";
    public string? ReceiverId { get; init; }
    public int Port { get; init; } = ReceiverOptions.DefaultPort;
    public string? PairingCode { get; init; }
    public string? TrustFile { get; init; }
    public bool Advertise { get; init; } = true;
    public LogLevel LogLevel { get; init; } = LogLevel.Information;
    public TimeSpan MarkerRotation { get; init; } = TimeSpan.FromSeconds(2);
}

/// <summary>A console "screen": shows the pairing code and aim marker as text, simulates playback.</summary>
public sealed class MockReceiverApp : IAsyncDisposable, IReceiverUi
{
    private readonly ILoggerFactory _logs;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(10) };

    private MockReceiverApp(MockReceiverOptions options)
    {
        _logs = LoggerFactory.Create(b => b.AddSimpleConsole(o => o.SingleLine = true).SetMinimumLevel(options.LogLevel));
        Playback = new SimulatedPlayback(_http);
        var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SwipeTo", "mock-receiver");
        Host = new ReceiverHost(
            new ReceiverOptions
            {
                ReceiverId = options.ReceiverId ?? PersistentId(Path.Combine(dataDir, "receiver-id.json")),
                Name = options.Name,
                Platform = "windows",
                Port = options.Port,
                Advertise = options.Advertise,
                FixedPairingCode = options.PairingCode,
                MarkerRotation = options.MarkerRotation,
            },
            Playback,
            this,
            new JsonFileTrustStore(options.TrustFile ?? Path.Combine(dataDir, "trusted-senders.json")),
            _logs);
    }

    public ReceiverHost Host { get; }
    public SimulatedPlayback Playback { get; }

    /// <summary>Last marker shown, or null while hidden.</summary>
    public string? AimMarker { get; private set; }

    public bool AimLocked { get; private set; }

    public static async Task<MockReceiverApp> StartAsync(MockReceiverOptions options, CancellationToken ct = default)
    {
        var app = new MockReceiverApp(options);
        await app.Host.StartAsync(ct);
        return app;
    }

    public async ValueTask DisposeAsync()
    {
        await Host.DisposeAsync();
        _http.Dispose();
        _logs.Dispose();
    }

    public void ShowPairingCode(string code, string senderName) =>
        Console.WriteLine($"\n  ┌──────────────────────────────┐\n  │  Pair \"{senderName}\": {code}  \n  └──────────────────────────────┘\n");

    public void HidePairingCode() => Console.WriteLine("[screen] pairing code hidden");

    public void ShowAim(string markerPayload, bool locked)
    {
        var first = AimMarker is null || locked != AimLocked;
        AimMarker = markerPayload;
        AimLocked = locked;
        if (first) Console.WriteLine($"[screen] ▓▓ edge glow ON{(locked ? " (LOCKED)" : "")} ▓▓  marker: {markerPayload}");
    }

    public void HideAim()
    {
        if (AimMarker is null) return;
        AimMarker = null;
        AimLocked = false;
        Console.WriteLine("[screen] edge glow off");
    }

    private static string PersistentId(string path)
    {
        if (File.Exists(path) && JsonSerializer.Deserialize<string>(File.ReadAllText(path)) is { } saved) return saved;
        var id = Guid.NewGuid().ToString("D");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, JsonSerializer.Serialize(id));
        return id;
    }
}
