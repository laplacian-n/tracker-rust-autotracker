// Shared by the installer and the uninstaller.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32;

static partial class Build
{
    public const string Name = "Tracker-Rust";
    public const string Title = "Tracker-Rust autotracker";
    public const string Url = "https://github.com/laplacian-n/tracker-rust-autotracker";
    public const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Tracker-Rust";
    public const string ListFile = "install-files.txt";
    public static readonly string[] Extensions = { ".trk", ".trz" };

    /// <summary>
    /// The Java runtime cannot start from a folder whose path has characters outside
    /// ASCII (its launcher works in the ANSI code page), e.g. a Thai user name.
    /// </summary>
    public static bool IsAscii(string s)
    {
        foreach (char c in s) if (c > 127) return false;
        return true;
    }

    public static string DefaultDir
    {
        get
        {
            string d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                @"Programs\Tracker-Rust");
            if (IsAscii(d)) return d;
            string pub = Environment.GetEnvironmentVariable("PUBLIC");
            if (!string.IsNullOrEmpty(pub) && IsAscii(pub)) return Path.Combine(pub, "Tracker-Rust");
            return Path.GetPathRoot(Environment.SystemDirectory) + "Tracker-Rust";
        }
    }

    public static string StartMenuLink
    {
        get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), Name + ".lnk"); }
    }

    public static string DesktopLink
    {
        get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Name + ".lnk"); }
    }

    static string ProgId(string ext) { return "TrackerRust" + ext; }

    /// <summary>Java processes started from this install folder.</summary>
    public static List<Process> RunningIn(string dir)
    {
        List<Process> list = new List<Process>();
        string prefix = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
        foreach (Process p in Process.GetProcesses())
        {
            if (!p.ProcessName.StartsWith("java", StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                string path = p.MainModule.FileName;
                if (path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) list.Add(p);
            }
            catch { }
        }
        return list;
    }

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    class ShellLink { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder file, int cch, IntPtr fd, uint flags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder name, int cch);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder dir, int cch);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder args, int cch);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
        void GetHotkey(out short hotkey);
        void SetHotkey(short hotkey);
        void GetShowCmd(out int showCmd);
        void SetShowCmd(int showCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder path, int cch, out int icon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int icon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string rel, uint reserved);
        void Resolve(IntPtr hwnd, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
    }

    public static void CreateShortcut(string link, string target, string dir, string description)
    {
        IShellLinkW s = (IShellLinkW)new ShellLink();
        try
        {
            s.SetPath(target);
            s.SetWorkingDirectory(dir);
            s.SetDescription(description);
            s.SetIconLocation(target, 0);
            ((System.Runtime.InteropServices.ComTypes.IPersistFile)s).Save(link, true);
        }
        finally { Marshal.FinalReleaseComObject(s); }
    }

    /// <summary>The target of a shortcut, or null.</summary>
    public static string ShortcutTarget(string link)
    {
        IShellLinkW s = (IShellLinkW)new ShellLink();
        try
        {
            ((System.Runtime.InteropServices.ComTypes.IPersistFile)s).Load(link, 0);
            System.Text.StringBuilder sb = new System.Text.StringBuilder(1024);
            s.GetPath(sb, sb.Capacity, IntPtr.Zero, 0x4 /* SLGP_RAWPATH */);
            return sb.ToString();
        }
        catch { return null; }
        finally { Marshal.FinalReleaseComObject(s); }
    }

    [DllImport("shell32.dll")]
    static extern void SHChangeNotify(int eventId, int flags, IntPtr item1, IntPtr item2);

    public static void Associate(string exe)
    {
        foreach (string ext in Extensions)
        {
            string id = ProgId(ext);
            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + id))
            {
                k.SetValue("", ext == ".trz" ? "Tracker project (zip)" : "Tracker file");
                using (RegistryKey i = k.CreateSubKey("DefaultIcon")) i.SetValue("", "\"" + exe + "\",0");
                using (RegistryKey c = k.CreateSubKey(@"shell\open\command"))
                    c.SetValue("", "\"" + exe + "\" \"%1\"");
            }
            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + ext))
            {
                k.SetValue("", id);
                using (RegistryKey o = k.CreateSubKey("OpenWithProgids")) o.SetValue(id, new byte[0], RegistryValueKind.None);
            }
        }
        SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero);
    }

    public static void Unassociate()
    {
        foreach (string ext in Extensions)
        {
            string id = ProgId(ext);
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(@"Software\Classes\" + ext, true))
            {
                if (k != null)
                {
                    if (id.Equals(k.GetValue("") as string)) k.DeleteValue("", false);
                    using (RegistryKey o = k.OpenSubKey("OpenWithProgids", true))
                        if (o != null) o.DeleteValue(id, false);
                }
            }
            Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\" + id, false);
        }
        SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero);
    }

    /// <summary>
    /// Removes what an earlier install put in dir: the entries named in the
    /// install list (or, for the first zip/bat release, its known files).
    /// Never deletes the folder's other contents.
    /// </summary>
    public static void RemoveInstalledFiles(string dir, bool all)
    {
        List<string> names = new List<string>();
        string list = Path.Combine(dir, ListFile);
        if (File.Exists(list)) names.AddRange(File.ReadAllLines(list));
        names.AddRange(new[] { "Tracker-Rust.bat", "tracker-native.jar", "tmnative.dll", "README.txt", "LICENSE" });
        if (all) names.AddRange(new[] { "tracker.prefs", ListFile });
        foreach (string raw in names)
        {
            string n = raw.Trim();
            if (n.Length == 0 || n.Contains("..") || Path.IsPathRooted(n)) continue;
            string p = Path.Combine(dir, n);
            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    if (Directory.Exists(p)) Directory.Delete(p, true);
                    else if (File.Exists(p)) File.Delete(p);
                    break;
                }
                catch (IOException) { if (attempt >= 20) throw; System.Threading.Thread.Sleep(250); }
                catch (UnauthorizedAccessException) { if (attempt >= 20) throw; System.Threading.Thread.Sleep(250); }
            }
        }
    }
}
