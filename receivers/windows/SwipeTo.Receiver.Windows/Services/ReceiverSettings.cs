using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>Persistent receiver settings in <c>settings.json</c> under the data directory.</summary>
public sealed class ReceiverSettings
{
    public string ReceiverId { get; set; } = Guid.NewGuid().ToString("D");
    public string Name { get; set; } = Environment.MachineName;
    public bool FirstRunDone { get; set; }

    /// <summary>The user's YouTube Data API key, encrypted for this Windows user (DPAPI); never stored in clear.</summary>
    public string? YouTubeKeyProtected { get; set; }

    [JsonIgnore]
    public string? YouTubeKey
    {
        get
        {
            if (YouTubeKeyProtected is null) return null;
            try
            {
                return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(YouTubeKeyProtected), null, DataProtectionScope.CurrentUser));
            }
            catch (CryptographicException)
            {
                return null; // another user's or machine's settings file
            }
        }
        set => YouTubeKeyProtected = string.IsNullOrWhiteSpace(value)
            ? null
            : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value.Trim()), null, DataProtectionScope.CurrentUser));
    }

    public static ReceiverSettings Load(string dataDir)
    {
        var path = PathIn(dataDir);
        var settings = File.Exists(path) ? JsonSerializer.Deserialize<ReceiverSettings>(File.ReadAllText(path)) ?? new() : new();
        settings.Save(dataDir); // pins the generated receiver id on first run
        return settings;
    }

    public void Save(string dataDir)
    {
        Directory.CreateDirectory(dataDir);
        File.WriteAllText(PathIn(dataDir), JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static string PathIn(string dataDir) => Path.Combine(dataDir, "settings.json");
}

/// <summary>The per-user "Run" registry entry that starts the receiver at sign-in.</summary>
public static class StartWithWindows
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "SwipeTo Receiver";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(ValueName, $"\"{Environment.ProcessPath}\" --background");
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}
