using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using Makaretu.Dns;

namespace SwipeTo.Net;

/// <summary>What a receiver puts in its mDNS TXT record (docs/protocol.md, section 2).</summary>
public sealed record ReceiverAdvertisement(string Id, string Name, string Platform, int Port, int Version = 1, string Caps = "video,image");

/// <summary>A receiver found on the network, with every address it advertised.</summary>
public sealed record DiscoveredReceiver(
    string Id, string Name, string Platform, int Version, IReadOnlyList<string> Caps, IReadOnlyList<IPAddress> Addresses, int Port)
{
    public Uri WebSocketUri(IPAddress address) =>
        new($"ws://{(address.AddressFamily == AddressFamily.InterNetworkV6 ? $"[{address}]" : address.ToString())}:{Port}/v1");

    /// <summary>IPv4 first: link-local IPv6 needs a scope id that not every stack round-trips.</summary>
    public IEnumerable<Uri> WebSocketUris() =>
        Addresses.OrderBy(a => a.AddressFamily == AddressFamily.InterNetwork ? 0 : 1).Select(WebSocketUri);
}

public static class Mdns
{
    public const string ServiceName = "_swipeto._tcp";
    internal static readonly DomainName QualifiedServiceName = new(ServiceName + ".local");
}

/// <summary>Advertises one receiver as <c>_swipeto._tcp.local.</c> until disposed.</summary>
public sealed class ReceiverAdvertiser : IDisposable
{
    private readonly MulticastService _mdns;
    private readonly ServiceDiscovery _discovery;
    private readonly ServiceProfile _profile;

    public ReceiverAdvertiser(ReceiverAdvertisement ad)
    {
        // Dots would split the instance name into DNS labels.
        var instance = ad.Name.Replace('.', ' ');
        _profile = new ServiceProfile(instance, Mdns.ServiceName, checked((ushort)ad.Port));
        _profile.AddProperty("id", ad.Id);
        _profile.AddProperty("name", ad.Name);
        _profile.AddProperty("platform", ad.Platform);
        _profile.AddProperty("ver", ad.Version.ToString(CultureInfo.InvariantCulture));
        _profile.AddProperty("caps", ad.Caps);
        _profile.AddProperty("port", ad.Port.ToString(CultureInfo.InvariantCulture));

        _mdns = new MulticastService();
        _discovery = new ServiceDiscovery(_mdns);
        _discovery.Advertise(_profile);
        _mdns.Start();
        _discovery.Announce(_profile);
    }

    public void Dispose()
    {
        try
        {
            _discovery.Unadvertise(_profile);
        }
        catch (Exception e) when (e is SocketException or ObjectDisposedException or InvalidOperationException)
        {
            // Goodbye packets are best effort.
        }
        _discovery.Dispose();
        _mdns.Dispose();
    }
}

/// <summary>
/// Browses for receivers. Collects PTR, SRV, TXT and A/AAAA records from every mDNS answer, asks for
/// whatever is still missing, and raises <see cref="ReceiverFound"/> once a receiver is complete.
/// </summary>
public sealed class ReceiverBrowser : IDisposable
{
    private readonly MulticastService _mdns = new();
    private readonly object _gate = new();
    private readonly HashSet<string> _instances = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, (string Target, int Port)> _srv = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, Dictionary<string, string>> _txt = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, HashSet<IPAddress>> _addresses = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, DiscoveredReceiver> _found = new();
    private readonly CancellationTokenSource _stop = new();

    public event Action<DiscoveredReceiver>? ReceiverFound;

    public IReadOnlyCollection<DiscoveredReceiver> Found => _found.Values.ToList();

    public void Start()
    {
        _mdns.AnswerReceived += (_, e) => OnAnswer(e.Message);
        _mdns.Start();
        _ = Task.Run(async () =>
        {
            // Ask often at first, then back off: receivers also announce themselves when they start.
            var delay = TimeSpan.FromMilliseconds(250);
            while (!_stop.IsCancellationRequested)
            {
                _mdns.SendQuery(Mdns.QualifiedServiceName, type: DnsType.PTR);
                try { await Task.Delay(delay, _stop.Token); } catch (OperationCanceledException) { return; }
                delay = TimeSpan.FromMilliseconds(Math.Min(delay.TotalMilliseconds * 2, 5000));
            }
        });
    }

    /// <summary>Browses until <paramref name="receiverId"/> appears or <paramref name="timeout"/> passes.</summary>
    public static async Task<DiscoveredReceiver?> FindAsync(string receiverId, TimeSpan timeout, CancellationToken ct = default)
    {
        using var browser = new ReceiverBrowser();
        var found = new TaskCompletionSource<DiscoveredReceiver>(TaskCreationOptions.RunContinuationsAsynchronously);
        browser.ReceiverFound += r =>
        {
            if (string.Equals(r.Id, receiverId, StringComparison.OrdinalIgnoreCase)) found.TrySetResult(r);
        };
        browser.Start();
        var winner = await Task.WhenAny(found.Task, Task.Delay(timeout, ct));
        return winner == found.Task ? found.Task.Result : null;
    }

    /// <summary>Browses for <paramref name="duration"/> and returns everything found.</summary>
    public static async Task<IReadOnlyCollection<DiscoveredReceiver>> BrowseAsync(TimeSpan duration, CancellationToken ct = default)
    {
        using var browser = new ReceiverBrowser();
        browser.Start();
        await Task.Delay(duration, ct);
        return browser.Found;
    }

    public void Dispose()
    {
        _stop.Cancel();
        _mdns.Dispose();
        _stop.Dispose();
    }

    private void OnAnswer(Makaretu.Dns.Message message)
    {
        var complete = new List<DiscoveredReceiver>();
        var missing = new List<(DomainName Name, DnsType Type)>();
        lock (_gate)
        {
            foreach (var record in message.Answers.Concat(message.AdditionalRecords))
            {
                var name = record.Name.ToString();
                switch (record)
                {
                    case PTRRecord ptr when record.Name.Equals(Mdns.QualifiedServiceName):
                        _instances.Add(ptr.DomainName.ToString());
                        break;
                    case SRVRecord srv:
                        _srv[name] = (srv.Target.ToString(), srv.Port);
                        break;
                    case TXTRecord txt:
                        _txt[name] = ParseTxt(txt.Strings);
                        break;
                    case ARecord a:
                        AddressesOf(name).Add(a.Address);
                        break;
                    case AAAARecord aaaa:
                        AddressesOf(name).Add(aaaa.Address);
                        break;
                }
            }

            foreach (var instance in _instances)
            {
                var hasSrv = _srv.TryGetValue(instance, out var srv);
                var hasTxt = _txt.TryGetValue(instance, out var txt);
                if (!hasSrv) missing.Add((new DomainName(instance), DnsType.SRV));
                if (!hasTxt) missing.Add((new DomainName(instance), DnsType.TXT));
                if (!hasSrv || !hasTxt) continue;
                if (!_addresses.TryGetValue(srv.Target, out var addresses) || addresses.Count == 0)
                {
                    missing.Add((new DomainName(srv.Target), DnsType.A));
                    continue;
                }
                if (ToReceiver(txt!, srv.Port, addresses) is { } receiver) complete.Add(receiver);
            }
        }

        foreach (var (name, type) in missing.Distinct()) _mdns.SendQuery(name, type: type);
        foreach (var receiver in complete)
        {
            var isNew = true;
            _found.AddOrUpdate(receiver.Id, receiver, (_, old) =>
            {
                isNew = !old.Addresses.SequenceEqual(receiver.Addresses) || old.Port != receiver.Port || old.Name != receiver.Name;
                return receiver;
            });
            if (isNew) ReceiverFound?.Invoke(receiver);
        }
    }

    private HashSet<IPAddress> AddressesOf(string host)
    {
        if (!_addresses.TryGetValue(host, out var set)) _addresses[host] = set = [];
        return set;
    }

    private static Dictionary<string, string> ParseTxt(IEnumerable<string> strings) =>
        strings.Select(s => s.Split('=', 2))
            .Where(kv => kv.Length == 2)
            .GroupBy(kv => kv[0], StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.First()[1], StringComparer.OrdinalIgnoreCase);

    private static DiscoveredReceiver? ToReceiver(Dictionary<string, string> txt, int port, IEnumerable<IPAddress> addresses)
    {
        if (!txt.TryGetValue("id", out var id) || !Guid.TryParse(id, out _)) return null;
        var version = txt.TryGetValue("ver", out var ver) && int.TryParse(ver, CultureInfo.InvariantCulture, out var v) ? v : 1;
        return new DiscoveredReceiver(
            id.ToLowerInvariant(),
            txt.GetValueOrDefault("name", "Screen"),
            txt.GetValueOrDefault("platform", "unknown"),
            version,
            txt.GetValueOrDefault("caps", "").Split(',', StringSplitOptions.RemoveEmptyEntries),
            addresses.OrderBy(a => a.ToString(), StringComparer.Ordinal).ToList(),
            port);
    }
}
