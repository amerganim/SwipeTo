using System.Text.Json;

namespace SwipeTo.Net.Receiver;

public sealed record TrustedSender(string SenderId, string SenderName, DateTimeOffset PairedAt);

/// <summary>Senders this receiver has paired with. Only token hashes are kept.</summary>
public interface ITrustStore
{
    bool IsTrusted(string senderId, string trustToken);
    void Add(string senderId, string senderName, string trustToken);
    bool Revoke(string senderId);
    IReadOnlyList<TrustedSender> List();
}

public sealed class InMemoryTrustStore : ITrustStore
{
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _gate = new();

    internal IEnumerable<Entry> Entries
    {
        get { lock (_gate) return _entries.Values.ToList(); }
    }

    public bool IsTrusted(string senderId, string trustToken)
    {
        lock (_gate)
        {
            return _entries.TryGetValue(senderId, out var entry) && Secrets.FixedTimeEquals(entry.TokenSha256, Secrets.Sha256(trustToken));
        }
    }

    public void Add(string senderId, string senderName, string trustToken)
    {
        lock (_gate) _entries[senderId] = new Entry(senderId, senderName, Secrets.Sha256(trustToken), DateTimeOffset.UtcNow);
        Changed();
    }

    public bool Revoke(string senderId)
    {
        bool removed;
        lock (_gate) removed = _entries.Remove(senderId);
        if (removed) Changed();
        return removed;
    }

    public IReadOnlyList<TrustedSender> List()
    {
        lock (_gate) return _entries.Values.Select(e => new TrustedSender(e.SenderId, e.SenderName, e.PairedAt)).ToList();
    }

    internal void Load(IEnumerable<Entry> entries)
    {
        lock (_gate)
        {
            foreach (var entry in entries) _entries[entry.SenderId] = entry;
        }
    }

    internal Action Changed { get; set; } = () => { };

    internal sealed record Entry(string SenderId, string SenderName, string TokenSha256, DateTimeOffset PairedAt);
}

/// <summary>A trust store persisted as JSON. Holds SHA-256 hashes of tokens, never the tokens.</summary>
public sealed class JsonFileTrustStore : ITrustStore
{
    private readonly string _path;
    private readonly InMemoryTrustStore _inner = new();

    public JsonFileTrustStore(string path)
    {
        _path = path;
        if (File.Exists(path))
        {
            var entries = JsonSerializer.Deserialize<List<InMemoryTrustStore.Entry>>(File.ReadAllText(path)) ?? [];
            _inner.Load(entries);
        }
        _inner.Changed = Save;
    }

    public bool IsTrusted(string senderId, string trustToken) => _inner.IsTrusted(senderId, trustToken);

    public void Add(string senderId, string senderName, string trustToken) => _inner.Add(senderId, senderName, trustToken);

    public bool Revoke(string senderId) => _inner.Revoke(senderId);

    public IReadOnlyList<TrustedSender> List() => _inner.List();

    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
        var temp = _path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(_inner.Entries, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, _path, overwrite: true);
    }
}
