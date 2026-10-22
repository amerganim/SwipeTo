using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace SwipeTo.Net.Receiver;

/// <summary>
/// A complete SwipeTo receiver minus the screen: WebSocket server at <c>/v1</c>, mDNS advertising,
/// pairing, sessions and playback control. Platforms supply an <see cref="IPlaybackTarget"/> and an
/// <see cref="IReceiverUi"/>.
/// </summary>
public sealed class ReceiverHost : IAsyncDisposable
{
    private readonly ILoggerFactory _logs;
    private readonly ILogger _log;
    private readonly object _gate = new();
    private WebApplication? _app;
    private ReceiverAdvertiser? _advertiser;
    private ReceiverConnection? _active;

    public ReceiverHost(ReceiverOptions options, IPlaybackTarget playback, IReceiverUi ui, ITrustStore trust, ILoggerFactory? logs = null)
    {
        Options = options;
        Playback = playback;
        Ui = ui;
        Trust = trust;
        _logs = logs ?? NullLoggerFactory.Instance;
        _log = _logs.CreateLogger<ReceiverHost>();
    }

    public ReceiverOptions Options { get; }
    internal IPlaybackTarget Playback { get; }
    internal IReceiverUi Ui { get; }
    public ITrustStore Trust { get; }

    /// <summary>The nonce in this screen's QR marker; shared by every connection, since there is one screen.</summary>
    public MarkerNonces Markers { get; } = new();

    /// <summary>The port actually listened on (useful when <see cref="ReceiverOptions.Port"/> is 0).</summary>
    public int Port { get; private set; }

    /// <summary>Raised when a sender's session starts or ends, with its display name.</summary>
    public event Action<string>? SenderConnected;
    public event Action<string>? SenderDisconnected;

    public async Task StartAsync(CancellationToken ct = default)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(k => k.ListenAnyIP(Options.Port));
        _app = builder.Build();
        _app.UseWebSockets();
        _app.Map("/v1", HandleWebSocketAsync);
        await _app.StartAsync(ct);

        var address = _app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        Port = new Uri(address.Replace("[::]", "localhost", StringComparison.Ordinal)).Port;

        if (Options.Advertise)
        {
            _advertiser = new ReceiverAdvertiser(new ReceiverAdvertisement(Options.ReceiverId, Options.Name, Options.Platform, Port, Caps: Options.Caps, Services: Options.Services));
        }
        _log.LogInformation("Receiver {Name} listening on port {Port}", Options.Name, Port);
    }

    public async ValueTask DisposeAsync()
    {
        _advertiser?.Dispose();
        if (_app is not null)
        {
            await _app.StopAsync();
            await _app.DisposeAsync();
        }
    }

    /// <summary>
    /// Claims playback for <paramref name="connection"/>. Fails if another sender holds an active
    /// handoff on a live connection; the same sender reconnecting may take over.
    /// </summary>
    internal bool TryClaim(ReceiverConnection connection)
    {
        lock (_gate)
        {
            if (_active is not null && _active != connection && _active.IsOpen && _active.SenderId != connection.SenderId) return false;
            _active = connection;
            return true;
        }
    }

    internal bool IsBusyFor(string senderId)
    {
        lock (_gate) return _active is not null && _active.IsOpen && _active.SenderId != senderId;
    }

    internal bool IsActive(ReceiverConnection connection)
    {
        lock (_gate) return _active == connection;
    }

    internal void Release(ReceiverConnection connection)
    {
        lock (_gate)
        {
            if (_active == connection) _active = null;
        }
    }

    internal void OnSenderConnected(string name) => SenderConnected?.Invoke(name);

    internal void OnSenderDisconnected(string name) => SenderDisconnected?.Invoke(name);

    private async Task HandleWebSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }
        var socket = await context.WebSockets.AcceptWebSocketAsync();
        var connection = new ReceiverConnection(this, socket, _logs.CreateLogger<ReceiverConnection>());
        await connection.RunAsync(context.RequestAborted);
    }
}
