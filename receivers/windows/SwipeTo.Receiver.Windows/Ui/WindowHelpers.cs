using System.Runtime.InteropServices;
using Microsoft.UI;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Windows.Graphics;

namespace SwipeTo.Receiver.Windows.Ui;

internal static partial class WindowHelpers
{
    private const int GwlExStyle = -20;
    private const long WsExToolWindow = 0x00000080;
    private const long WsExNoActivate = 0x08000000;
    private const long WsExTopmost = 0x00000008;

    public static AppWindow AppWindowOf(Window window) =>
        AppWindow.GetFromWindowId(Win32Interop.GetWindowIdFromWindow(WinRT.Interop.WindowNative.GetWindowHandle(window)));

    /// <summary>Borderless, always on top, never takes focus, not in the taskbar or Alt+Tab.</summary>
    public static AppWindow MakeOverlay(Window window)
    {
        var appWindow = AppWindowOf(window);
        var presenter = OverlappedPresenter.Create();
        presenter.SetBorderAndTitleBar(false, false);
        presenter.IsAlwaysOnTop = true;
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.IsMinimizable = false;
        appWindow.SetPresenter(presenter);
        appWindow.IsShownInSwitchers = false;

        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(window);
        var style = GetWindowLongPtr(hwnd, GwlExStyle);
        SetWindowLongPtr(hwnd, GwlExStyle, (nint)((long)style | WsExToolWindow | WsExNoActivate | WsExTopmost));
        return appWindow;
    }

    /// <summary>A small titled window that stays on top (pairing code, first run).</summary>
    public static AppWindow MakeDialog(Window window, int width, int height)
    {
        var appWindow = AppWindowOf(window);
        var presenter = OverlappedPresenter.Create();
        presenter.IsAlwaysOnTop = true;
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.IsMinimizable = false;
        appWindow.SetPresenter(presenter);
        var scale = ScaleOf(window);
        var area = DisplayArea.Primary.WorkArea;
        var (w, h) = ((int)(width * scale), (int)(height * scale));
        appWindow.MoveAndResize(new RectInt32(area.X + (area.Width - w) / 2, area.Y + (area.Height - h) / 2, w, h));
        return appWindow;
    }

    /// <summary>Raw pixels per effective pixel for the display the window is on.</summary>
    public static double ScaleOf(Window window) => GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(window)) / 96.0;

    /// <summary>Runs <paramref name="action"/> on the UI thread and waits for it.</summary>
    public static Task<T> RunAsync<T>(this DispatcherQueue queue, Func<T> action)
    {
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!queue.TryEnqueue(() =>
            {
                try { tcs.SetResult(action()); }
                catch (Exception e) { tcs.SetException(e); }
            }))
        {
            tcs.SetException(new InvalidOperationException("UI thread is shutting down"));
        }
        return tcs.Task;
    }

    public static Task RunAsync(this DispatcherQueue queue, Action action) => queue.RunAsync(() =>
    {
        action();
        return true;
    });

    /// <summary>Windows 11 draws a 1 px border even on borderless windows; turn it off (no-op on Windows 10).</summary>
    public static void RemoveDwmBorder(Window window)
    {
        var none = DwmColorNone;
        _ = DwmSetWindowAttribute(WinRT.Interop.WindowNative.GetWindowHandle(window), DwmwaBorderColor, ref none, sizeof(uint));
    }

    private const int DwmwaBorderColor = 34;
    private const uint DwmColorNone = 0xFFFFFFFE;

    [LibraryImport("dwmapi.dll")]
    private static partial int DwmSetWindowAttribute(nint hwnd, int attribute, ref uint value, int size);

    [LibraryImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static partial nint GetWindowLongPtr(nint hwnd, int index);

    [LibraryImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static partial nint SetWindowLongPtr(nint hwnd, int index, nint value);

    [LibraryImport("user32.dll")]
    private static partial uint GetDpiForWindow(nint hwnd);
}
