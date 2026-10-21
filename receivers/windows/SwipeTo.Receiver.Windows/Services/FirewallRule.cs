using System.ComponentModel;
using System.Diagnostics;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>
/// An inbound Windows Firewall rule for this executable on private networks only, so phones on the
/// home Wi-Fi can reach the WebSocket server and mDNS. Adding it needs elevation (one UAC prompt).
/// </summary>
public static class FirewallRule
{
    public const string RuleName = "SwipeTo Receiver";

    public static bool Exists()
    {
        try
        {
            var (exitCode, _) = Netsh($"advfirewall firewall show rule name=\"{RuleName}\"", elevated: false);
            return exitCode == 0;
        }
        catch (Win32Exception)
        {
            return false;
        }
    }

    /// <summary>Asks for elevation and adds the rule. Returns false if the user declined UAC or netsh failed.</summary>
    public static bool Add()
    {
        try
        {
            var (exitCode, _) = Netsh(AddArguments(Environment.ProcessPath!), elevated: true);
            return exitCode == 0;
        }
        catch (Win32Exception)
        {
            return false; // UAC declined
        }
    }

    public static string AddArguments(string programPath) =>
        $"advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow enable=yes profile=private program=\"{programPath}\"";

    private static (int ExitCode, string Output) Netsh(string arguments, bool elevated)
    {
        var info = new ProcessStartInfo("netsh.exe", arguments)
        {
            UseShellExecute = elevated,
            Verb = elevated ? "runas" : "",
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardOutput = !elevated,
        };
        using var process = Process.Start(info)!;
        var output = elevated ? "" : process.StandardOutput.ReadToEnd();
        process.WaitForExit(15_000);
        return (process.ExitCode, output);
    }
}
