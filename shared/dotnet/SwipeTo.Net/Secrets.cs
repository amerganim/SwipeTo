using System.Security.Cryptography;
using System.Text;

namespace SwipeTo.Net;

/// <summary>Random values the protocol needs, all from a CSPRNG.</summary>
public static class Secrets
{
    /// <summary>Six uniformly random digits, leading zeros kept.</summary>
    public static string PairingCode() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    /// <summary>128+ bits, base64url without padding: trust tokens, media session tokens.</summary>
    public static string Token(int bytes = 32) => Base64Url(RandomNumberGenerator.GetBytes(bytes));

    public static string Base64Url(byte[] data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static string Sha256(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));

    public static bool FixedTimeEquals(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
}
