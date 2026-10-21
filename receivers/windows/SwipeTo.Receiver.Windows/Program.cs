using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace SwipeTo.Receiver.Windows;

public static class Program
{
    /// <summary>Keeps the application object reachable for the lifetime of the UI thread.</summary>
    public static App? Current { get; private set; }

    [STAThread]
    private static int Main(string[] args)
    {
        var options = LaunchOptions.Parse(args);

        // One receiver per user session: a second launch would fight over the port and the tray.
        using var instance = new Mutex(initiallyOwned: true, $@"Local\SwipeTo.Receiver.{options.InstanceKey}", out var isFirst);
        if (!isFirst) return 0;

        WinRT.ComWrappersSupport.InitializeComWrappers();
        Application.Start(_ =>
        {
            SynchronizationContext.SetSynchronizationContext(new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread()));
            Current = new App(options);
        });
        return 0;
    }
}
