using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SwipeTo.Receiver.Windows.Tests;

/// <summary>Opt-in: these tests open full-screen windows on the primary display.</summary>
public sealed class E2EFactAttribute : FactAttribute
{
    public E2EFactAttribute()
    {
        if (Environment.GetEnvironmentVariable("SWIPETO_E2E") != "1")
        {
            Skip = "Set SWIPETO_E2E=1 to run (launches the receiver full screen). Build receivers/windows first.";
        }
    }
}

/// <summary>The built SwipeTo.Receiver.exe, started with its own data directory, port and fixed pairing code.</summary>
public sealed partial class ReceiverProcess : IAsyncDisposable
{
    public const string PairingCode = "246810";

    private readonly Process _process;

    private ReceiverProcess(Process process, string dataDir, int port, string receiverId)
    {
        _process = process;
        DataDir = dataDir;
        Port = port;
        ReceiverId = receiverId;
    }

    public string DataDir { get; }
    public int Port { get; }
    public string ReceiverId { get; }
    public Uri LocalUri => new($"ws://127.0.0.1:{Port}/v1");

    public static async Task<ReceiverProcess> StartAsync()
    {
        var dataDir = Directory.CreateTempSubdirectory("swipeto-receiver-e2e-").FullName;
        var port = FreePort();
        var process = Process.Start(new ProcessStartInfo(FindExecutable(),
            ["--data-dir", dataDir, "--port", port.ToString(), "--pair-code", PairingCode, "--name", "E2E screen"]))!;

        var deadline = DateTime.UtcNow.AddSeconds(30);
        while (DateTime.UtcNow < deadline)
        {
            if (process.HasExited) throw new InvalidOperationException($"Receiver exited with {process.ExitCode}");
            if (LogText(dataDir).Contains("listening on port", StringComparison.Ordinal))
            {
                var settings = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(dataDir, "settings.json")));
                return new ReceiverProcess(process, dataDir, port, settings.RootElement.GetProperty("ReceiverId").GetString()!);
            }
            await Task.Delay(200);
        }
        process.Kill();
        throw new TimeoutException("Receiver did not start listening within 30 s");
    }

    public string Log => LogText(DataDir);

    /// <summary>Waits for a log line matching <paramref name="pattern"/> and returns the match.</summary>
    public async Task<Match> WaitForLogAsync(Regex pattern, TimeSpan? timeout = null)
    {
        var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(10));
        while (DateTime.UtcNow < deadline)
        {
            var match = pattern.Match(Log);
            if (match.Success) return match;
            await Task.Delay(100);
        }
        throw new TimeoutException($"No log line matching {pattern}. Log:\n{Log}");
    }

    /// <summary>What Alt+F4 does: ask the player window to close.</summary>
    public void ClosePlayerWindow()
    {
        var player = FindWindowsOfProcess(_process.Id).First(h => WindowTitle(h) == "SwipeTo" && IsWindowVisible(h));
        PostMessageW(player, 0x0010 /* WM_CLOSE */, 0, 0);
    }

    public async ValueTask DisposeAsync()
    {
        if (!_process.HasExited)
        {
            _process.Kill();
            await _process.WaitForExitAsync();
        }
        _process.Dispose();
        try { Directory.Delete(DataDir, recursive: true); } catch (IOException) { }
    }

    private static string LogText(string dataDir)
    {
        var logs = Path.Combine(dataDir, "logs");
        if (!Directory.Exists(logs)) return "";
        return string.Concat(Directory.GetFiles(logs, "*.log").Select(f =>
        {
            using var reader = new StreamReader(new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite));
            return reader.ReadToEnd();
        }));
    }

    private static int FreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    private static string FindExecutable()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var bin = Path.Combine(dir.FullName, "receivers", "windows", "SwipeTo.Receiver.Windows", "bin");
            if (!Directory.Exists(bin)) continue;
            var exe = Directory.GetFiles(bin, "SwipeTo.Receiver.exe", SearchOption.AllDirectories)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (exe is not null) return exe;
        }
        throw new FileNotFoundException("Build receivers/windows/SwipeTo.Receiver.Windows first (SwipeTo.Receiver.exe not found)");
    }

    private static List<nint> FindWindowsOfProcess(int pid)
    {
        var found = new List<nint>();
        EnumWindows((hwnd, _) =>
        {
            GetWindowThreadProcessId(hwnd, out var owner);
            if (owner == pid) found.Add(hwnd);
            return true;
        }, 0);
        return found;
    }

    private static string WindowTitle(nint hwnd)
    {
        var buffer = new char[256];
        var length = GetWindowTextW(hwnd, buffer, buffer.Length);
        return new string(buffer, 0, length);
    }

    private delegate bool EnumWindowsProc(nint hwnd, nint lParam);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, nint lParam);

    [LibraryImport("user32.dll")]
    private static partial uint GetWindowThreadProcessId(nint hwnd, out int processId);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextW(nint hwnd, char[] text, int count);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool IsWindowVisible(nint hwnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool PostMessageW(nint hwnd, uint message, nint wParam, nint lParam);
}
