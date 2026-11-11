// Uninstall.exe: removes Tracker-Rust. Copies itself to %TEMP% first so the
// install folder can be deleted, then removes that copy when done.
//
//   Uninstall.exe        asks first
//   Uninstall.exe /S     silent
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Tracker-Rust Uninstall")]
[assembly: AssemblyProduct("Tracker-Rust autotracker")]
[assembly: AssemblyVersion(Build.Version)]
[assembly: AssemblyFileVersion(Build.Version)]

static class Uninstall
{
    const string Caption = "Uninstall Tracker-Rust";

    [STAThread]
    static int Main(string[] args)
    {
        bool silent = false;
        string dir = null;
        int parent = 0;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i].Equals("/S", StringComparison.OrdinalIgnoreCase)) silent = true;
            else if (args[i] == "/run" && i + 2 < args.Length) { dir = args[++i]; int.TryParse(args[++i], out parent); }
        }

        if (dir == null)
        {
            // first stage, inside the install folder
            dir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
            if (!silent && MessageBox.Show("Remove Tracker-Rust from\n" + dir + " ?\n\nYour videos and Tracker files are not touched.",
                    Caption, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return 1;
            string tmp = Path.Combine(Path.GetTempPath(), "Tracker-Rust-uninstall-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".exe");
            File.Copy(Assembly.GetExecutingAssembly().Location, tmp, true);
            ProcessStartInfo psi = new ProcessStartInfo(tmp,
                "/run \"" + dir + "\" " + Process.GetCurrentProcess().Id + (silent ? " /S" : ""));
            psi.UseShellExecute = false;
            psi.WorkingDirectory = Path.GetTempPath();
            Process.Start(psi);
            return 0;
        }

        // second stage, running from %TEMP%
        try { if (parent != 0) Process.GetProcessById(parent).WaitForExit(10000); } catch { }
        int code = 0;
        try
        {
            while (Build.RunningIn(dir).Count > 0)
            {
                if (silent) { code = 2; goto cleanup; }
                if (MessageBox.Show("Tracker-Rust is running. Please close it, then press Retry.", Caption,
                        MessageBoxButtons.RetryCancel, MessageBoxIcon.Information) != DialogResult.Retry) { code = 1; goto cleanup; }
            }
            DeleteLink(Build.StartMenuLink, dir);
            DeleteLink(Build.DesktopLink, dir);
            Build.Unassociate();
            Registry.CurrentUser.DeleteSubKeyTree(Build.UninstallKey, false);
            Build.RemoveInstalledFiles(dir, true);
            try { if (Directory.GetFileSystemEntries(dir).Length == 0) Directory.Delete(dir); } catch { }
            if (!silent) MessageBox.Show("Tracker-Rust was removed.", Caption, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception e)
        {
            code = 1;
            if (!silent) MessageBox.Show("Could not remove everything:\n" + e.Message, Caption, MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    cleanup:
        // delete this temporary copy after it exits
        string self = Assembly.GetExecutingAssembly().Location;
        ProcessStartInfo del = new ProcessStartInfo("cmd.exe", "/c ping 127.0.0.1 -n 3 >nul & del /f /q \"" + self + "\"");
        del.CreateNoWindow = true; del.UseShellExecute = false;
        del.WindowStyle = ProcessWindowStyle.Hidden;
        try { Process.Start(del); } catch { }
        return code;
    }

    static void DeleteLink(string link, string dir)
    {
        // only our own shortcut (it points into the install folder)
        try
        {
            if (!File.Exists(link)) return;
            string target = Build.ShortcutTarget(link);
            if (target != null && target.StartsWith(dir + "\\", StringComparison.OrdinalIgnoreCase)) File.Delete(link);
        }
        catch { }
    }
}
