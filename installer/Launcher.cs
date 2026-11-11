// Tracker-Rust.exe: starts Tracker with the bundled Java runtime, Xuggle and
// the Rust autotracker, without a console window.
//
// Memory: half of the physical RAM (1-16 GB). Override with the environment
// variable TRACKER_MEMORY_MB.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Tracker-Rust")]
[assembly: System.Reflection.AssemblyProduct("Tracker-Rust autotracker")]
[assembly: System.Reflection.AssemblyVersion(Build.Version)]
[assembly: System.Reflection.AssemblyFileVersion(Build.Version)]

static class Launcher
{
    [StructLayout(LayoutKind.Sequential)]
    class MEMORYSTATUSEX
    {
        public uint dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX));
        public uint dwMemoryLoad;
        public ulong ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile,
                     ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GlobalMemoryStatusEx([In, Out] MEMORYSTATUSEX m);

    static long MemoryMb()
    {
        string env = Environment.GetEnvironmentVariable("TRACKER_MEMORY_MB");
        long mb;
        if (env != null && long.TryParse(env.Trim(), out mb) && mb >= 256) return mb;
        MEMORYSTATUSEX m = new MEMORYSTATUSEX();
        if (!GlobalMemoryStatusEx(m)) return 2048;
        mb = (long)(m.ullTotalPhys / 2 / (1024 * 1024));
        return Math.Min(16384, Math.Max(1024, mb));
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern int GetShortPathName(string longPath, StringBuilder shortPath, int size);

    // A file opened from Explorer (e.g. a .trz in Documents) may have non-ASCII
    // characters in its path; give Java the 8.3 short form, which is ASCII.
    static string AsciiPath(string arg)
    {
        bool ascii = true;
        foreach (char c in arg) if (c > 127) { ascii = false; break; }
        if (ascii || !(File.Exists(arg) || Directory.Exists(arg))) return arg;
        StringBuilder sb = new StringBuilder(1024);
        int n = GetShortPathName(arg, sb, sb.Capacity);
        return n > 0 && n < sb.Capacity ? sb.ToString() : arg;
    }

    static string Quote(string a)
    {
        if (a.Length > 0 && a.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return a;
        StringBuilder sb = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in a)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') sb.Append('\\', slashes * 2 + 1);
            else sb.Append('\\', slashes);
            slashes = 0;
            sb.Append(c);
        }
        sb.Append('\\', slashes * 2).Append('"');
        return sb.ToString();
    }

    [STAThread]
    static int Main(string[] args)
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        string javaw = Path.Combine(dir, @"jre\bin\javaw.exe");
        if (!File.Exists(javaw) || !File.Exists(Path.Combine(dir, "tracker-native.jar")))
        {
            MessageBox.Show("Tracker-Rust is incomplete (Java runtime or tracker-native.jar is missing).\n" +
                "Please run the installer again.", "Tracker-Rust", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        foreach (char c in dir)
            if (c > 127)
            {
                MessageBox.Show("Java cannot run from a folder whose name has non-English characters:\n" + dir +
                    "\n\nPlease install Tracker-Rust again into a folder with English letters only.",
                    "Tracker-Rust", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        long mem = MemoryMb();

        ProcessStartInfo psi = new ProcessStartInfo(javaw);
        psi.UseShellExecute = false;
        psi.WorkingDirectory = dir;
        // Tracker relaunches itself with its own (1 GB) memory setting unless the
        // environment already matches; these make it keep this JVM and its heap.
        psi.EnvironmentVariables["TRACKER_HOME"] = dir;
        psi.EnvironmentVariables["XUGGLE_HOME"] = Path.Combine(dir, "Xuggle");
        psi.EnvironmentVariables["MEMORY_SIZE"] = mem.ToString();
        psi.EnvironmentVariables["PATH"] = Path.Combine(dir, @"Xuggle\bin") + ";" +
            Environment.GetEnvironmentVariable("PATH");

        // Paths inside the install folder are relative on purpose: Java reads its
        // command line in the ANSI code page and cannot open folder names with,
        // for example, Thai characters given there.
        StringBuilder a = new StringBuilder();
        a.Append("-Dsun.awt.fontconfig=fontconfig.properties ");
        a.Append("-Xms64m -Xmx").Append(mem).Append("m -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent ");
        a.Append("-cp tracker-native.jar;Xuggle\\xuggle-xuggler-server-all.jar;Xuggle\\slf4j-api.jar ");
        a.Append("org.opensourcephysics.cabrillo.tracker.Tracker");
        foreach (string arg in args) a.Append(' ').Append(Quote(AsciiPath(arg)));
        psi.Arguments = a.ToString();

        try { Process.Start(psi); }
        catch (Exception e)
        {
            MessageBox.Show("Could not start Java:\n" + e.Message, "Tracker-Rust",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        return 0;
    }
}
