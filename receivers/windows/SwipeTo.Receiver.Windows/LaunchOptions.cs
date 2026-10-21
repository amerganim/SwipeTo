using System.Globalization;

namespace SwipeTo.Receiver.Windows;

/// <summary>
/// Command-line switches. Normal users never pass any; they exist so the receiver can be driven by
/// tests and the mock sender without touching the user's real settings.
/// </summary>
public sealed record LaunchOptions
{
    /// <summary>Settings, trust store and logs go here instead of %LOCALAPPDATA%\SwipeTo\Receiver.</summary>
    public string? DataDir { get; init; }

    public int? Port { get; init; }
    public string? Name { get; init; }

    /// <summary>Testing only: always use this pairing code.</summary>
    public string? PairingCode { get; init; }

    /// <summary>Started by the "Start with Windows" entry: stay quiet, no first-run window.</summary>
    public bool Background { get; init; }

    public string InstanceKey => DataDir is null ? "default" : Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(DataDir)))[..16];

    public static LaunchOptions Parse(string[] args)
    {
        var options = new LaunchOptions();
        for (var i = 0; i < args.Length; i++)
        {
            string Next() => i + 1 < args.Length ? args[++i] : "";
            options = args[i] switch
            {
                "--data-dir" => options with { DataDir = Path.GetFullPath(Next()) },
                "--port" => options with { Port = int.Parse(Next(), CultureInfo.InvariantCulture) },
                "--name" => options with { Name = Next() },
                "--pair-code" => options with { PairingCode = Next() },
                "--background" => options with { Background = true },
                _ => options,
            };
        }
        return options;
    }
}
