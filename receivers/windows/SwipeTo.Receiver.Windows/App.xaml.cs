using System.Diagnostics;
using System.Windows.Input;
using H.NotifyIcon;
using H.NotifyIcon.Core;
using Microsoft.Extensions.Logging;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SwipeTo.Net.Receiver;
using SwipeTo.Receiver.Windows.Services;
using SwipeTo.Receiver.Windows.Ui;

namespace SwipeTo.Receiver.Windows;

/// <summary>
/// Tray-only app: no main window. Owns the <see cref="ReceiverHost"/> and restarts it when the
/// receiver is renamed (the name is part of the mDNS advertisement).
/// </summary>
public partial class App : Application
{
    private readonly LaunchOptions _options;
    private readonly string _dataDir;
    private readonly ReceiverSettings _settings;
    private readonly FileLoggerProvider _fileLogs;
    private readonly ILoggerFactory _logs;
    private readonly ILogger _log;
    private DispatcherQueue _ui = null!;
    private ReceiverHost? _host;
    private TaskbarIcon? _tray;
    private MenuFlyoutItem? _statusItem;
    private string _status = "starting";

    public App(LaunchOptions options)
    {
        _options = options;
        _dataDir = options.DataDir ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SwipeTo", "Receiver");
        _settings = ReceiverSettings.Load(_dataDir);
        if (options.Name is { } name) _settings.Name = name;
        _fileLogs = new FileLoggerProvider(Path.Combine(_dataDir, "logs"));
        _logs = LoggerFactory.Create(b => b.AddProvider(_fileLogs).SetMinimumLevel(LogLevel.Information));
        _log = _logs.CreateLogger<App>();

        InitializeComponent();
        // The receiver lives in the tray; closing the player or pairing window must not quit it.
        DispatcherShutdownMode = DispatcherShutdownMode.OnExplicitShutdown;
        UnhandledException += (_, e) => _log.LogError(e.Exception, "Unhandled UI exception");
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        _ui = DispatcherQueue.GetForCurrentThread();
        _log.LogInformation("SwipeTo receiver {Version} starting, data in {Dir}", typeof(App).Assembly.GetName().Version, _dataDir);
        CreateTray();
        await StartHostAsync();

        if (!_settings.FirstRunDone && !_options.Background && _options.PairingCode is null)
        {
            new FirstRunWindow(_settings.Name, RenameAsync, _host?.Trust, firstRun: true).Activate();
            _settings.FirstRunDone = true;
            _settings.Save(_dataDir);
        }
    }

    private async Task StartHostAsync()
    {
        var playback = new WinUiPlayback(_ui, _dataDir, _logs.CreateLogger<WinUiPlayback>());
        var ui = new WinUiReceiverUi(_ui, () => _settings.Name, _logs.CreateLogger<WinUiReceiverUi>(), _options.MarkerLeft);
        playback.WarmUp();
        ui.WarmUp();
        var host = new ReceiverHost(
            new ReceiverOptions
            {
                ReceiverId = _settings.ReceiverId,
                Name = _settings.Name,
                Platform = "windows",
                Port = _options.Port ?? ReceiverOptions.DefaultPort,
                FixedPairingCode = _options.PairingCode,
            },
            playback,
            ui,
            new JsonFileTrustStore(Path.Combine(_dataDir, "trusted-senders.json")),
            _logs);
        host.SenderConnected += sender =>
        {
            SetStatus($"connected to {sender}");
            playback.HideNotice();
        };
        host.SenderDisconnected += sender =>
        {
            SetStatus("ready");
            // Playback carries on without the phone (docs/protocol.md, 5.7); say so, and how to stop it.
            if (!playback.Snapshot.Ended) playback.ShowNotice($"{sender} disconnected. Still playing here. Press Esc to close.");
        };

        try
        {
            await host.StartAsync();
            _host = host;
            SetStatus("ready");
        }
        catch (Exception e) when (e is IOException or System.Net.Sockets.SocketException)
        {
            _log.LogError(e, "Could not start the receiver");
            SetStatus($"port {host.Options.Port} is in use");
            await host.DisposeAsync();
        }
    }

    private async void RenameAsync(string name)
    {
        if (name == _settings.Name) return;
        _settings.Name = name;
        _settings.Save(_dataDir);
        if (_host is not null) await _host.DisposeAsync();
        _host = null;
        await StartHostAsync();
    }

    private void CreateTray()
    {
        _statusItem = new MenuFlyoutItem { IsEnabled = false };
        var autostart = new ToggleMenuFlyoutItem { Text = "Start with Windows", IsChecked = StartWithWindows.IsEnabled };
        autostart.Command = new Command(() => StartWithWindows.Set(!StartWithWindows.IsEnabled));

        var menu = new MenuFlyout();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(new MenuFlyoutItem { Text = "Settings…", Command = new Command(ShowSettings) });
        menu.Items.Add(autostart);
        menu.Items.Add(new MenuFlyoutItem { Text = "Open log folder", Command = new Command(() => OpenFolder(Path.Combine(_dataDir, "logs"))) });
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(new MenuFlyoutItem { Text = "Quit SwipeTo", Command = new Command(Quit) });

        _tray = new TaskbarIcon
        {
            ToolTipText = "SwipeTo",
            ContextMenuMode = ContextMenuMode.SecondWindow,
            ContextFlyout = menu,
            NoLeftClickDelay = true,
            LeftClickCommand = new Command(ShowSettings),
            IconSource = new GeneratedIconSource
            {
                Text = "S",
                FontSize = 36,
                Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.White),
                Background = (Microsoft.UI.Xaml.Media.Brush)Resources["SwipeToAccentBrush"],
            },
        };
        _tray.ForceCreate(enablesEfficiencyMode: false);
        SetStatus(_status);
    }

    private void ShowSettings() => new FirstRunWindow(_settings.Name, RenameAsync, _host?.Trust, firstRun: false).Activate();

    private void SetStatus(string status)
    {
        _status = status;
        _ui?.TryEnqueue(() =>
        {
            if (_statusItem is not null) _statusItem.Text = $"{_settings.Name} — {status}";
            if (_tray is not null) _tray.ToolTipText = $"SwipeTo: {_settings.Name} ({status})";
        });
    }

    private async void Quit()
    {
        _log.LogInformation("Quitting");
        if (_host is not null) await _host.DisposeAsync();
        _tray?.Dispose();
        _logs.Dispose();
        _fileLogs.Dispose();
        Exit();
    }

    private static void OpenFolder(string path)
    {
        Directory.CreateDirectory(path);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = true });
    }

    private sealed class Command(Action execute) : ICommand
    {
        public event EventHandler? CanExecuteChanged { add { } remove { } }

        public bool CanExecute(object? parameter) => true;

        public void Execute(object? parameter) => execute();
    }
}
