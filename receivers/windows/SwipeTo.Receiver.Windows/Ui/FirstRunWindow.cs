using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SwipeTo.Receiver.Windows.Services;

namespace SwipeTo.Receiver.Windows.Ui;

/// <summary>
/// Shown once: names this screen, offers the private-network firewall rule and start-with-Windows,
/// and says where the app lives afterwards (the tray).
/// </summary>
internal sealed class FirstRunWindow : Window
{
    public FirstRunWindow(string receiverName, Action<string> rename)
    {
        Title = "SwipeTo receiver";
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

        var done = new Button { Content = "Done", HorizontalAlignment = HorizontalAlignment.Right, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        done.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(name.Text)) rename(name.Text.Trim());
            Close();
        };

        var panel = new StackPanel { Spacing = 14, Margin = new Thickness(28) };
        panel.Children.Add(new TextBlock { Text = "This PC can now receive from your phone", FontSize = 22, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(new TextBlock
        {
            Text = "Open SwipeTo on your phone, pick this screen, and type the code that appears here. After that, SwipeTo waits quietly in the notification area.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.8,
        });
        panel.Children.Add(name);
        panel.Children.Add(firewall);
        panel.Children.Add(firewallStatus);
        panel.Children.Add(autostart);
        panel.Children.Add(done);
        Content = new ScrollViewer { Content = panel };
        WindowHelpers.MakeDialog(this, 520, 520);
    }
}
