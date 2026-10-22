using System.Globalization;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;

namespace SwipeTo.Receiver.Windows.Ui;

/// <summary>What YouTube's player last reported: position, state (YouTube's numbers) and any error.</summary>
public readonly record struct YouTubeStatus(long PositionMs, int State, int? Error)
{
    public const int Unstarted = -1, Ended = 0, Playing = 1, Paused = 2, Buffering = 3, Cued = 5;
}

/// <summary>
/// YouTube's official IFrame player, full size, in a WebView2. The page is served from a local
/// virtual host (https://swipeto.player/) so YouTube sees a proper origin. The video comes from
/// YouTube itself, ads and all; SwipeTo only tells it which video, where, and play/pause/seek.
/// </summary>
internal sealed class YouTubeView
{
    private const string Host = "swipeto.player";
    private readonly WebView2 _web = new() { Visibility = Visibility.Collapsed };
    private readonly string _folder;
    private Task? _ready;
    private TaskCompletionSource? _api;

    public YouTubeView(string dataDir)
    {
        _folder = Path.Combine(dataDir, "player");
    }

    public UIElement Element => _web;

    /// <summary>Raised on the UI thread for every report from the player (about twice a second, and on changes).</summary>
    public event Action<YouTubeStatus>? Status;

    /// <summary>Shows the player and starts <paramref name="videoId"/> at <paramref name="positionMs"/>.</summary>
    public async Task LoadAsync(string videoId, long positionMs, bool playing)
    {
        await (_ready ??= InitializeAsync());
        _web.Visibility = Visibility.Visible;
        var seconds = (positionMs / 1000.0).ToString("0.###", CultureInfo.InvariantCulture);
        await Run($"load({JsonSerializer.Serialize(videoId)}, {seconds}, {(playing ? "true" : "false")})");
    }

    /// <summary>Loads the browser and YouTube's player script ahead of the first handoff (7 s the first time).</summary>
    public Task WarmUpAsync() => _ready ??= InitializeAsync();

    public Task PlayAsync() => Run("player && player.playVideo()");

    public Task PauseAsync() => Run("player && player.pauseVideo()");

    public Task SeekAsync(long positionMs) =>
        Run($"player && player.seekTo({(positionMs / 1000.0).ToString("0.###", CultureInfo.InvariantCulture)}, true)");

    /// <summary>Stops and hides the player (the page stays loaded for the next video).</summary>
    public async Task StopAsync()
    {
        _web.Visibility = Visibility.Collapsed;
        if (_ready is { IsCompletedSuccessfully: true }) await Run("player && player.stopVideo()");
    }

    private async Task Run(string script)
    {
        if (_ready is not { IsCompletedSuccessfully: true }) return;
        await _web.CoreWebView2.ExecuteScriptAsync(script);
    }

    private async Task InitializeAsync()
    {
        Directory.CreateDirectory(_folder);
        await File.WriteAllTextAsync(Path.Combine(_folder, "youtube.html"), Page);
        await _web.EnsureCoreWebView2Async();
        var core = _web.CoreWebView2;
        core.SetVirtualHostNameToFolderMapping(Host, _folder, CoreWebView2HostResourceAccessKind.Allow);
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        // Links inside the player (the YouTube logo, end screens) must not navigate away.
        core.NewWindowRequested += (_, e) => e.Handled = true;
        _api = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        core.WebMessageReceived += (_, e) => OnMessage(e.TryGetWebMessageAsString());
        core.Navigate($"https://{Host}/youtube.html");
        await _api.Task.WaitAsync(TimeSpan.FromSeconds(20));
    }

    private void OnMessage(string? json)
    {
        if (json is null) return;
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        switch (root.GetProperty("ev").GetString())
        {
            case "api":
                _api?.TrySetResult();
                break;
            case "error":
                Status?.Invoke(new YouTubeStatus(0, YouTubeStatus.Ended, root.GetProperty("code").GetInt32()));
                break;
            default:
                var seconds = root.TryGetProperty("t", out var t) && t.ValueKind == JsonValueKind.Number ? t.GetDouble() : 0;
                var state = root.TryGetProperty("s", out var s) && s.ValueKind == JsonValueKind.Number ? s.GetInt32() : YouTubeStatus.Unstarted;
                Status?.Invoke(new YouTubeStatus((long)(seconds * 1000), state, null));
                break;
        }
    }

    // The player page. postMessage reports go to OnMessage.
    private const string Page = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
        <body><div id="p"></div><script>
        var player = null;
        function post(o) { window.chrome.webview.postMessage(JSON.stringify(o)); }
        var tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        document.head.appendChild(tag);
        function onYouTubeIframeAPIReady() { post({ ev: 'api' }); }
        function load(id, start, play) {
          if (player) {
            if (play) player.loadVideoById({ videoId: id, startSeconds: start });
            else player.cueVideoById({ videoId: id, startSeconds: start });
            return;
          }
          player = new YT.Player('p', {
            videoId: id, width: '100%', height: '100%',
            playerVars: { autoplay: play ? 1 : 0, start: Math.floor(start), controls: 1, rel: 0, playsinline: 1, fs: 0, iv_load_policy: 3, origin: location.origin },
            events: {
              onReady: function (e) { e.target.seekTo(start, true); if (play) e.target.playVideo(); else e.target.pauseVideo(); },
              onStateChange: function (e) { post({ ev: 'state', s: e.data, t: player.getCurrentTime() }); },
              onError: function (e) { post({ ev: 'error', code: e.data }); }
            }
          });
        }
        setInterval(function () {
          if (player && player.getCurrentTime) post({ ev: 'tick', t: player.getCurrentTime(), s: player.getPlayerState() });
        }, 500);
        </script></body></html>
        """;
}
