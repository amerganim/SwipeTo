using Microsoft.Extensions.Logging;
using Microsoft.UI.Dispatching;
using SwipeTo.Net.Receiver;
using SwipeTo.Receiver.Windows.Ui;

namespace SwipeTo.Receiver.Windows.Services;

/// <summary>Pairing code window and aim overlay, marshalled to the UI thread.</summary>
internal sealed class WinUiReceiverUi(DispatcherQueue ui, Func<string> receiverName, ILogger log) : IReceiverUi
{
    private readonly AimOverlay _aim = new();
    private PairingWindow? _pairing;

    public void ShowPairingCode(string code, string senderName) => ui.TryEnqueue(() =>
    {
        _pairing ??= NewPairingWindow();
        _pairing.Show(code, senderName);
        log.LogInformation("Pairing code shown for {Sender}", senderName);
    });

    public void HidePairingCode() => ui.TryEnqueue(() =>
    {
        _pairing?.Close();
        _pairing = null;
    });

    /// <summary>UI thread: creates the overlay windows hidden.</summary>
    public void WarmUp() => _aim.WarmUp();

    public void ShowAim(string markerPayload) => ui.TryEnqueue(async () =>
    {
        if (await _aim.ShowAsync(markerPayload, receiverName())) log.LogInformation("Aim glow and marker shown");
    });

    public void HideAim() => ui.TryEnqueue(() =>
    {
        var wasVisible = _aim.IsVisible;
        _aim.Hide();
        if (wasVisible) log.LogInformation("Aim glow and marker hidden");
    });

    private PairingWindow NewPairingWindow()
    {
        var window = new PairingWindow();
        window.Closed += (_, _) =>
        {
            if (_pairing == window) _pairing = null;
        };
        return window;
    }
}
