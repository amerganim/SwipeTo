using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace SwipeTo.Receiver.Windows.Ui;

/// <summary>Shows the 6-digit pairing code large enough to read from the sofa.</summary>
internal sealed class PairingWindow : Window
{
    private readonly TextBlock _who = new() { FontSize = 20, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _code = new()
    {
        FontSize = 72,
        FontWeight = FontWeights.SemiBold,
        CharacterSpacing = 300,
        TextAlignment = TextAlignment.Center,
        FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas"),
    };

    public PairingWindow()
    {
        Title = "SwipeTo — pair a phone";
        var panel = new StackPanel { Spacing = 12, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(24) };
        panel.Children.Add(_who);
        panel.Children.Add(_code);
        panel.Children.Add(new TextBlock
        {
            Text = "Type this code on your phone. It expires in 60 seconds.",
            Opacity = 0.7,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
        });
        Content = panel;
        WindowHelpers.MakeDialog(this, 520, 300);
    }

    public void Show(string code, string senderName)
    {
        _who.Text = $"Pair with “{senderName}”";
        _code.Text = $"{code[..3]} {code[3..]}";
        Activate();
    }
}
