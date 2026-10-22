using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SwipeTo.Net.Receiver;
using SwipeTo.Receiver.Windows.Services;

namespace SwipeTo.Receiver.Windows.Ui;

/// <summary>
/// First run and Settings in one window: how to connect a phone, this screen's name, the
/// private-network firewall rule, start with Windows, and the phones that are trusted (with revoke).
/// </summary>
internal sealed class FirstRunWindow : Window
{
    private readonly ITrustStore? _trust;
    private readonly StackPanel _phones = new() { Spacing = 6 };

    public FirstRunWindow(string receiverName, Action<string> rename, ITrustStore? trust, bool firstRun, (bool Has, Action<string?> Set) youTubeKey)
    {
        _trust = trust;
        Title = firstRun ? "Welcome to SwipeTo" : "SwipeTo settings";
        var name = new TextBox { Header = "Name phones will see", Text = receiverName, MaxLength = 64 };

        var firewallStatus = new TextBlock { Opacity = 0.7, TextWrapping = TextWrapping.Wrap };
        var firewall = new Button { Content = "Allow on private networks…" };
        void RefreshFirewall()
        {
            var exists = FirewallRule.Exists();
            firewall.IsEnabled = !exists;
            firewallStatus.Text = exists
                ? "Phones on your home Wi-Fi can reach this PC."
                : "Windows Firewall may block phones until you allow SwipeTo on private networks (asks for administrator approval).";
        }
        firewall.Click += (_, _) =>
        {
            FirewallRule.Add();
            RefreshFirewall();
        };
        RefreshFirewall();

        var autostart = new CheckBox { Content = "Start SwipeTo when I sign in", IsChecked = StartWithWindows.IsEnabled };
        autostart.Click += (_, _) => StartWithWindows.Set(autostart.IsChecked == true);

        // YouTube: the phone only knows the title and channel; the receiver finds the video with the
        // official YouTube Data API, using the user's own key. Only title and channel go to Google.
        var youTube = new PasswordBox
        {
            Header = "YouTube API key (to open YouTube videos swiped from the phone)",
            PlaceholderText = youTubeKey.Has ? "Saved. Type a new key to replace it" : "Paste your YouTube Data API v3 key",
        };
        var youTubeHelp = new TextBlock
        {
            Text = "Create one at console.cloud.google.com: new project → enable \"YouTube Data API v3\" → Credentials → Create API key. " +
                   "It is stored encrypted for your Windows account. SwipeTo sends YouTube only the title and channel of what you swiped.",
            Opacity = 0.7,
            TextWrapping = TextWrapping.Wrap,
        };

        var done = new Button { Content = "Done", HorizontalAlignment = HorizontalAlignment.Right, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        done.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(name.Text)) rename(name.Text.Trim());
            if (!string.IsNullOrWhiteSpace(youTube.Password)) youTubeKey.Set(youTube.Password);
            Close();
        };

        var panel = new StackPanel { Spacing = 14, Margin = new Thickness(28) };
        panel.Children.Add(new TextBlock
        {
            Text = firstRun ? "This PC can now receive from your phone" : "SwipeTo settings",
            FontSize = 22,
            FontWeight = FontWeights.SemiBold,
            TextWrapping = TextWrapping.Wrap,
        });
        if (firstRun)
        {
            panel.Children.Add(Steps(
                "1  Install SwipeTo on your Android phone and join the same Wi-Fi as this PC.",
                "2  Open a video in SwipeTo. This PC appears under “Screens nearby”: tap Send.",
                "3  Type the 6-digit code that appears on this screen. That pairs the phone once.",
                "4  From then on: lift the phone toward this screen, then swipe up on the video. Swipe down to bring it back."));
        }
        panel.Children.Add(name);
        panel.Children.Add(firewall);
        panel.Children.Add(firewallStatus);
        panel.Children.Add(autostart);
        panel.Children.Add(youTube);
        panel.Children.Add(youTubeHelp);
        if (trust is not null)
        {
            panel.Children.Add(new TextBlock { Text = "Trusted phones", FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 8, 0, 0) });
            panel.Children.Add(_phones);
            RefreshPhones();
        }
        panel.Children.Add(done);
        Content = new ScrollViewer { Content = panel };
        WindowHelpers.MakeDialog(this, 540, firstRun ? 680 : 600);
    }

    private static StackPanel Steps(params string[] lines)
    {
        var steps = new StackPanel { Spacing = 6 };
        foreach (var line in lines) steps.Children.Add(new TextBlock { Text = line, TextWrapping = TextWrapping.Wrap, Opacity = 0.85 });
        return steps;
    }

    private void RefreshPhones()
    {
        _phones.Children.Clear();
        var phones = _trust!.List().OrderByDescending(p => p.PairedAt).ToList();
        if (phones.Count == 0)
        {
            _phones.Children.Add(new TextBlock { Text = "No phones paired yet.", Opacity = 0.7 });
            return;
        }
        foreach (var phone in phones)
        {
            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var label = new TextBlock
            {
                Text = $"{phone.SenderName}  ·  paired {phone.PairedAt.LocalDateTime:d MMM yyyy}",
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
            };
            var revoke = new Button { Content = "Revoke" };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(revoke, $"Revoke {phone.SenderName}");
            revoke.Click += (_, _) =>
            {
                // The phone must pair again (6-digit code) before it can hand off here.
                _trust.Revoke(phone.SenderId);
                RefreshPhones();
            };
            Grid.SetColumn(revoke, 1);
            row.Children.Add(label);
            row.Children.Add(revoke);
            _phones.Children.Add(row);
        }
    }
}
