using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using QRCoder;

namespace SwipeTo.Receiver.Windows.Ui;

/// <summary>
/// The "this screen is the target" signal: a glow that fades in from every edge of the primary
/// display, plus a QR marker card in the bottom-right corner that a phone camera can read.
/// <para>
/// One full-screen layered Win32 window with per-pixel alpha: transparent in the middle,
/// click-through, always on top, never focused, not in the taskbar. (WinUI windows cannot be partly
/// transparent, and they do not render when shown without activation.)
/// </para>
/// </summary>
internal sealed partial class AimOverlay : IDisposable
{
    private const int GlowThickness = 28;
    private const int MarkerSize = 240;
    private const int MarkerMargin = 40;
    private const int CaptionHeight = 30;
    private static readonly Color Accent = Color.FromArgb(0x00, 0xB7, 0xFF);

    private nint _hwnd;
    private int _generation;

    public bool IsVisible { get; private set; }

    /// <summary>UI thread. Creates the window hidden so the first aim shows instantly.</summary>
    public void WarmUp() => EnsureWindow();

    /// <summary>
    /// UI thread. Rendering runs on a worker, so a <see cref="Hide"/> can arrive meanwhile; then
    /// nothing is shown. Returns whether the overlay was shown.
    /// </summary>
    public async Task<bool> ShowAsync(string payload, string receiverName)
    {
        var generation = ++_generation;
        EnsureWindow();
        var area = DisplayArea.Primary.OuterBounds;
        var scale = GetDpiForWindow(_hwnd) / 96.0;
        using var frame = await Task.Run(() => Render(area.Width, area.Height, scale, payload, receiverName));
        if (generation != _generation) return false;

        Present(frame, area.X, area.Y);
        ShowWindow(_hwnd, SwShowNoActivate);
        SetWindowPos(_hwnd, HwndTopmost, 0, 0, 0, 0, SwpNoMove | SwpNoSize | SwpNoActivate);
        IsVisible = true;
        return true;
    }

    /// <summary>UI thread.</summary>
    public void Hide()
    {
        _generation++;
        if (_hwnd != 0) ShowWindow(_hwnd, SwHide);
        IsVisible = false;
    }

    public void Dispose()
    {
        if (_hwnd != 0) DestroyWindow(_hwnd);
        _hwnd = 0;
    }

    private void EnsureWindow()
    {
        if (_hwnd != 0) return;
        var instance = GetModuleHandleW(null);
        var wc = new WndClassEx
        {
            cbSize = (uint)Marshal.SizeOf<WndClassEx>(),
            lpfnWndProc = NativeLibrary.GetExport(NativeLibrary.Load("user32.dll"), "DefWindowProcW"),
            hInstance = instance,
            lpszClassName = ClassName,
        };
        RegisterClassExW(ref wc); // fails harmlessly if already registered
        _hwnd = CreateWindowExW(
            WsExLayered | WsExTransparent | WsExTopmost | WsExToolWindow | WsExNoActivate,
            ClassName, "SwipeTo aim", WsPopup, 0, 0, 1, 1, 0, 0, instance, 0);
    }

    /// <summary>ARGB frame (GetHbitmap premultiplies it for UpdateLayeredWindow): edge glow plus the marker card.</summary>
    private static Bitmap Render(int width, int height, double scale, string payload, string receiverName)
    {
        var frame = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(frame);
        g.Clear(Color.Transparent);
        g.SmoothingMode = SmoothingMode.AntiAlias;

        // Glow: nested rectangles whose alpha falls off quadratically towards the inside.
        var glow = (int)(GlowThickness * scale);
        for (var i = 0; i < glow; i++)
        {
            var t = 1 - (double)i / glow;
            using var pen = new Pen(Color.FromArgb((int)(230 * t * t), Accent), 1);
            g.DrawRectangle(pen, i, i, width - 1 - 2 * i, height - 1 - 2 * i);
        }

        // Marker card: white, rounded, the QR code and the receiver's name under it.
        var size = (int)(MarkerSize * scale);
        var caption = (int)(CaptionHeight * scale);
        var margin = (int)(MarkerMargin * scale);
        var card = new Rectangle(width - size - margin, height - size - caption - margin, size, size + caption);
        using (var path = RoundedRect(card, (int)(16 * scale)))
        using (var white = new SolidBrush(Color.White))
        {
            g.FillPath(white, path);
        }
        using (var qr = QrBitmap(payload))
        {
            var pad = (int)(14 * scale);
            g.InterpolationMode = InterpolationMode.NearestNeighbor;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            g.DrawImage(qr, new Rectangle(card.X + pad, card.Y + pad, size - 2 * pad, size - 2 * pad));
        }
        using (var font = new Font("Segoe UI", (float)(13 * scale), FontStyle.Regular, GraphicsUnit.Pixel))
        using (var ink = new SolidBrush(Color.FromArgb(40, 40, 40)))
        using (var format = new StringFormat { Alignment = StringAlignment.Center, Trimming = StringTrimming.EllipsisCharacter })
        {
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
            g.DrawString($"SwipeTo · {receiverName}", font, ink, new RectangleF(card.X, card.Bottom - caption - (int)(6 * scale), card.Width, caption), format);
        }
        return frame;
    }

    private static Bitmap QrBitmap(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(pixelsPerModule: 8, drawQuietZones: false);
        using var stream = new MemoryStream(png);
        return new Bitmap(stream);
    }

    private static GraphicsPath RoundedRect(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    /// <summary>Hands the frame to the window with per-pixel alpha (UpdateLayeredWindow).</summary>
    private void Present(Bitmap frame, int x, int y)
    {
        var screen = GetDC(0);
        var memory = CreateCompatibleDC(screen);
        var bitmap = frame.GetHbitmap(Color.FromArgb(0));
        var old = SelectObject(memory, bitmap);
        try
        {
            var size = new SizeStruct(frame.Width, frame.Height);
            var source = new PointStruct(0, 0);
            var position = new PointStruct(x, y);
            var blend = new BlendFunction { BlendOp = 0, SourceConstantAlpha = 255, AlphaFormat = 1 }; // AC_SRC_ALPHA
            UpdateLayeredWindow(_hwnd, screen, ref position, ref size, memory, ref source, 0, ref blend, UlwAlpha);
        }
        finally
        {
            SelectObject(memory, old);
            DeleteObject(bitmap);
            DeleteDC(memory);
            ReleaseDC(0, screen);
        }
    }

    private const string ClassName = "SwipeToAimOverlay";
    private const uint WsPopup = 0x80000000;
    private const uint WsExLayered = 0x00080000;
    private const uint WsExTransparent = 0x00000020;
    private const uint WsExTopmost = 0x00000008;
    private const uint WsExToolWindow = 0x00000080;
    private const uint WsExNoActivate = 0x08000000;
    private const int SwHide = 0;
    private const int SwShowNoActivate = 4;
    private const uint UlwAlpha = 2;
    private const uint SwpNoSize = 0x0001;
    private const uint SwpNoMove = 0x0002;
    private const uint SwpNoActivate = 0x0010;
    private static readonly nint HwndTopmost = -1;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint cbSize;
        public uint style;
        public nint lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public nint hInstance;
        public nint hIcon;
        public nint hCursor;
        public nint hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public nint hIconSm;
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly record struct PointStruct(int X, int Y);

    [StructLayout(LayoutKind.Sequential)]
    private readonly record struct SizeStruct(int Cx, int Cy);

    [StructLayout(LayoutKind.Sequential)]
    private struct BlendFunction
    {
        public byte BlendOp;
        public byte BlendFlags;
        public byte SourceConstantAlpha;
        public byte AlphaFormat;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassExW(ref WndClassEx wc);

    [LibraryImport("user32.dll", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    private static partial nint CreateWindowExW(uint exStyle, string className, string windowName, uint style,
        int x, int y, int width, int height, nint parent, nint menu, nint instance, nint param);

    [LibraryImport("kernel32.dll", StringMarshalling = StringMarshalling.Utf16)]
    private static partial nint GetModuleHandleW(string? moduleName);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool ShowWindow(nint hwnd, int command);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetWindowPos(nint hwnd, nint after, int x, int y, int cx, int cy, uint flags);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DestroyWindow(nint hwnd);

    [LibraryImport("user32.dll")]
    private static partial uint GetDpiForWindow(nint hwnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool UpdateLayeredWindow(nint hwnd, nint dst, ref PointStruct position, ref SizeStruct size,
        nint src, ref PointStruct source, uint colorKey, ref BlendFunction blend, uint flags);

    [LibraryImport("user32.dll")]
    private static partial nint GetDC(nint hwnd);

    [LibraryImport("user32.dll")]
    private static partial int ReleaseDC(nint hwnd, nint dc);

    [LibraryImport("gdi32.dll")]
    private static partial nint CreateCompatibleDC(nint dc);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteDC(nint dc);

    [LibraryImport("gdi32.dll")]
    private static partial nint SelectObject(nint dc, nint obj);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteObject(nint obj);
}
