using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace SwipeTo.Net.Sender;

/// <summary>
/// The sender's local media server (docs/protocol.md, section 5.4): serves only files explicitly
/// registered for handoff, at <c>/m/&lt;sessionToken&gt;/&lt;fileId&gt;</c>, with HTTP Range support.
/// Anything else is a plain 404 that does not say which part was wrong.
/// </summary>
public sealed class MediaServer : IAsyncDisposable
{
    private readonly ConcurrentDictionary<string, (string Path, string Mime)> _files = new();
    private WebApplication? _app;

    /// <summary>Unguessable per-session token; changes on <see cref="EndSession"/>.</summary>
    public string SessionToken { get; private set; } = Secrets.Token(16);

    public int Port { get; private set; }

    public async Task StartAsync(int port = 0, CancellationToken ct = default)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(k => k.ListenAnyIP(port));
        _app = builder.Build();
        _app.MapMethods("/m/{token}/{fileId}", ["GET", "HEAD"], Serve);
        await _app.StartAsync(ct);
        var address = _app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        Port = new Uri(address.Replace("[::]", "localhost", StringComparison.Ordinal)).Port;
    }

    /// <summary>Makes <paramref name="path"/> downloadable for this session and returns its URL as seen from <paramref name="localAddress"/>.</summary>
    public string Register(string path, string mime, IPAddress localAddress)
    {
        var fileId = Secrets.Token(9);
        _files[fileId] = (Path.GetFullPath(path), mime);
        var host = localAddress.AddressFamily == AddressFamily.InterNetworkV6 ? $"[{localAddress}]" : localAddress.ToString();
        return $"http://{host}:{Port}/m/{SessionToken}/{fileId}";
    }

    /// <summary>Forgets every registered file and rotates the token, so old URLs stop working.</summary>
    public void EndSession()
    {
        _files.Clear();
        SessionToken = Secrets.Token(16);
    }

    /// <summary>The local address the OS would use to reach <paramref name="remote"/> (no packets are sent).</summary>
    public static IPAddress LocalAddressFor(IPAddress remote)
    {
        using var probe = new Socket(remote.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
        probe.Connect(remote, 9);
        return ((IPEndPoint)probe.LocalEndPoint!).Address;
    }

    public async ValueTask DisposeAsync()
    {
        if (_app is null) return;
        await _app.StopAsync();
        await _app.DisposeAsync();
    }

    private IResult Serve(string token, string fileId)
    {
        if (!Secrets.FixedTimeEquals(token, SessionToken) || !_files.TryGetValue(fileId, out var file) || !File.Exists(file.Path))
        {
            return Results.NotFound();
        }
        return Results.File(file.Path, file.Mime, enableRangeProcessing: true);
    }
}
