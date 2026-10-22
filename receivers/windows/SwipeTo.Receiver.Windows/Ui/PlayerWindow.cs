using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Transition = SwipeTo.Protocol.Transition;
using Windows.Media.Playback;
using Windows.System;

namespace SwipeTo.Receiver.Windows.Ui;

/// <summary>
/// Full-screen black window with either a video or a photo, plus a close button; Esc closes too.
/// Created once and reused: closing only hides it, so the next handoff does not pay for window
/// creation. <see cref="ViewerClosed"/> fires when the person in front of the screen closes it.
/// </summary>
internal sealed class PlayerWindow : Window
{
    private readonly AppWindow _appWindow;
    private readonly MediaPlayerElement _video = new() { AreTransportControlsEnabled = false, Stretch = Stretch.Uniform, AutoPlay = false };
    private readonly Image _photo = new() { Stretch = Stretch.Uniform, Visibility = Visibility.Collapsed };
    private readonly TextBlock _caption = new()
    {
        Foreground = new SolidColorBrush(Colors.White),
        Opacity = 0.7,
        FontSize = 18,
        Margin = new Thickness(32, 24, 32, 24),
        HorizontalAlignment = HorizontalAlignment.Left,
        VerticalAlignment = VerticalAlignment.Top,
    };
    private readonly TextBlock _notice = new()
    {
        Foreground = new SolidColorBrush(Colors.White),
        FontSize = 18,
        Margin = new Thickness(32),
        Padding = new Thickness(16, 10, 16, 10),
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Bottom,
        Visibility = Visibility.Collapsed,
    };

    public PlayerWindow(string dataDir)
    {
        YouTube = new YouTubeView(dataDir);
        Title = "SwipeTo";
        var close = new Button
        {
            Content = new FontIcon { Glyph = "", FontSize = 20 }, // Cancel (X)
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(24),
            Padding = new Thickness(14),
            Foreground = new SolidColorBrush(Colors.White),
            Background = new SolidColorBrush(global::Windows.UI.Color.FromArgb(0x99, 0, 0, 0)),
            BorderBrush = new SolidColorBrush(global::Windows.UI.Color.FromArgb(0x66, 0xFF, 0xFF, 0xFF)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(24),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(close, "Close");
        close.Click += (_, _) => ViewerClosed?.Invoke();

        var root = new Grid { Background = new SolidColorBrush(Colors.Black) };
        root.Children.Add(_video);
        root.Children.Add(YouTube.Element);
        root.Children.Add(_photo);
        root.Children.Add(_caption);
        root.Children.Add(new Border
        {
            Child = _notice,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 0, 48),
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(global::Windows.UI.Color.FromArgb(0xB0, 0, 0, 0)),
        });
        root.Children.Add(close);

        var escape = new KeyboardAccelerator { Key = VirtualKey.Escape };
        escape.Invoked += (_, e) =>
        {
            e.Handled = true;
            ViewerClosed?.Invoke();
        };
        root.KeyboardAccelerators.Add(escape);
        root.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden; // no "Esc" tooltip over the video
        Content = root;

        _appWindow = WindowHelpers.AppWindowOf(this);
        // Borderless and always on top rather than the full-screen presenter: Windows will not let a
        // background tray app bring a window to the foreground, so a normal window would open behind
        // whatever the viewer has focused.
        var presenter = OverlappedPresenter.Create();
        presenter.SetBorderAndTitleBar(false, false);
        presenter.IsAlwaysOnTop = true;
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.IsMinimizable = false;
        _appWindow.SetPresenter(presenter);
        WindowHelpers.RemoveDwmBorder(this);
        _appWindow.Closing += (_, e) =>
        {
            // Alt+F4 and friends: treat as the viewer closing, keep the window for reuse.
            e.Cancel = true;
            ViewerClosed?.Invoke();
        };
    }

    public event Action? ViewerClosed;

    /// <summary>YouTube's own player, for app content (docs/protocol.md, 5.4).</summary>
    public YouTubeView YouTube { get; }

    /// <summary>Full screen with YouTube's player (it loads the video itself, see <see cref="YouTube"/>).</summary>
    public void ShowYouTube(string caption)
    {
        _video.SetMediaPlayer(null);
        _video.Visibility = Visibility.Collapsed;
        _photo.Visibility = Visibility.Collapsed;
        _photo.Source = null;
        _caption.Text = caption;
        HideNotice();
        ShowFullScreen();
    }

    public bool IsShown => _appWindow.IsVisible;

    public void ShowVideo(MediaPlayer player, string caption)
    {
        _ = YouTube.StopAsync();
        _photo.Visibility = Visibility.Collapsed;
        _photo.Source = null;
        _video.SetMediaPlayer(player);
        _video.Visibility = Visibility.Visible;
        _video.Opacity = 0;
        _caption.Text = caption;
        HideNotice();
        ShowFullScreen();
    }

    /// <summary>Shows the video once it is at the handed-off position (black until then).</summary>
    public void RevealVideo() => _video.Opacity = 1;

    public void ShowPhoto(Uri url, string caption, Transition? transition = null)
    {
        _ = YouTube.StopAsync();
        _video.SetMediaPlayer(null);
        _video.Visibility = Visibility.Collapsed;
        _photo.Source = new BitmapImage(url);
        _photo.Visibility = Visibility.Visible;
        _caption.Text = caption;
        HideNotice();
        if (!IsShown) ShowFullScreen();
        if (transition is { } t) SlideIn(t);
    }

    /// <summary>Next photo comes in from the right, previous from the left.</summary>
    private void SlideIn(Transition transition)
    {
        var from = (transition == Transition.Next ? 1 : -1) * Math.Max(400, _photo.ActualWidth) * 0.6;
        var shift = new TranslateTransform { X = from };
        _photo.RenderTransform = shift;
        var slide = new DoubleAnimation
        {
            From = from,
            To = 0,
            Duration = new Duration(TimeSpan.FromMilliseconds(260)),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        var fade = new DoubleAnimation { From = 0.2, To = 1, Duration = new Duration(TimeSpan.FromMilliseconds(260)) };
        var board = new Storyboard();
        Storyboard.SetTarget(slide, shift);
        Storyboard.SetTargetProperty(slide, "X");
        Storyboard.SetTarget(fade, _photo);
        Storyboard.SetTargetProperty(fade, "Opacity");
        board.Children.Add(slide);
        board.Children.Add(fade);
        board.Begin();
    }

    /// <summary>A small line over the media, e.g. when the phone has gone away but playback continues.</summary>
    public void ShowNotice(string text)
    {
        _notice.Text = text;
        _notice.Visibility = Visibility.Visible;
    }

    public void HideNotice() => _notice.Visibility = Visibility.Collapsed;

    /// <summary>Hides the window and lets go of the media it was showing.</summary>
    public void HideAndReset()
    {
        _ = YouTube.StopAsync();
        _video.SetMediaPlayer(null);
        _photo.Source = null;
        _appWindow.Hide();
    }

    private void ShowFullScreen()
    {
        // Full screen on the primary display; choosing another display is a later setting.
        _appWindow.MoveAndResize(DisplayArea.Primary.OuterBounds);
        _appWindow.Show();
        Activate();
    }
}
