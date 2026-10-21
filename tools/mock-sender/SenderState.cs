using System.Text.Json;

namespace SwipeTo.MockSender;

/// <summary>The phone's persistent identity and the trust token issued by each receiver.</summary>
public sealed class SenderState
{
    public string SenderId { get; set; } = Guid.NewGuid().ToString("D");
    public Dictionary<string, string> TrustTokens { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    public static SenderState Load(string path) =>
        File.Exists(path) ? JsonSerializer.Deserialize<SenderState>(File.ReadAllText(path)) ?? new SenderState() : new SenderState();

    public void Save(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        File.WriteAllText(path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
